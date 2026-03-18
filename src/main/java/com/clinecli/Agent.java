package com.clinecli;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jline.reader.LineReader;

import java.util.*;

/**
 * Runs the agentic streaming loop: streams Claude's response, executes tools
 * (with approval for destructive ones), and loops until Claude stops calling tools.
 */
public class Agent {

    private static final String MODEL = "claude-opus-4-6";
    private static final String SYSTEM = """
            You are Cline, an expert software engineering assistant running in the terminal.
            You help with coding tasks, file operations, running commands, and debugging.

            You have tools to read/write files, run shell commands, search code, and edit
            files with targeted replacements.

            Current working directory: %s

            Guidelines:
            - Be concise. Briefly explain what you're about to do, then do it.
            - Prefer edit_file for small targeted changes over rewriting entire files with write_file.
            - If a task will take multiple steps, briefly outline them first.
            - When you encounter errors, diagnose the root cause before trying fixes.
            """.formatted(System.getProperty("user.dir"));

    private final AnthropicClient client;
    private final List<MessageParam> messages;
    private final LineReader lineReader;
    private final ObjectMapper json = new ObjectMapper();

    public Agent(AnthropicClient client, List<MessageParam> messages, LineReader lineReader) {
        this.client     = client;
        this.messages   = messages;
        this.lineReader = lineReader;
    }

    /** Run one user turn, potentially spanning multiple tool-use iterations. */
    public void runTurn() throws Exception {
        while (true) {
            StreamResult result = streamResponse();

            // Rebuild the assistant message from accumulated streaming data and store in history
            List<ContentBlockParam> assistantBlocks = buildAssistantBlocks(result);
            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.ASSISTANT)
                    .contentOfBlockParams(assistantBlocks)
                    .build());

            if (!StopReason.TOOL_USE.equals(result.stopReason)) {
                System.out.println();
                break;
            }

            // Execute each tool call and collect results
            List<ContentBlockParam> toolResults = new ArrayList<>();
            for (ToolCall tc : result.toolCalls) {
                Map<String, Object> input = parseJson(tc.inputJson);
                UI.printToolStart(tc.name, input);

                String resultText;
                if (Tools.isDestructive(tc.name)) {
                    boolean approved = confirm(Tools.approvalLabel(tc.name, input));
                    resultText = approved ? runTool(tc.name, input) : "User denied this action.";
                    if (!approved) UI.printToolDenied();
                } else {
                    resultText = runTool(tc.name, input);
                }

                toolResults.add(ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder()
                                .toolUseId(tc.id)
                                .content(resultText)
                                .build()));
            }

            // Feed all tool results back as a user turn and loop
            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .contentOfBlockParams(toolResults)
                    .build());
        }
    }

    // ── Streaming ─────────────────────────────────────────────────────────────

    private StreamResult streamResponse() {
        var paramsBuilder = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(8096)
                .system(SYSTEM)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .messages(messages);

        // addTool() one at a time (no addAllTools overload for List<Tool>)
        for (Tool tool : Tools.getTools()) {
            paramsBuilder.addTool(tool);
        }

        StreamResult acc = new StreamResult();

        try (var stream = client.messages().createStreaming(paramsBuilder.build())) {
            stream.stream().forEach(event -> {

                // ── Block start ──────────────────────────────────────────────
                event.contentBlockStart().ifPresent(cbs -> {
                    int idx = (int) cbs.index();
                    acc.currentIndex = idx;
                    var block = cbs.contentBlock();

                    if (block.isThinking()) {
                        acc.currentType = "thinking";
                        acc.thinkingTexts.put(idx, new StringBuilder());
                        acc.thinkingSignatures.put(idx, "");
                        if (!acc.prefixPrinted) {
                            System.out.print(UI.BOLD + UI.GREEN + "\nCline: " + UI.RESET);
                            acc.prefixPrinted = true;
                        }
                        System.out.print(UI.DIM + "💭 thinking…\n" + UI.RESET);
                        acc.lastWasThinking = true;

                    } else if (block.isText()) {
                        boolean wasThinking = acc.lastWasThinking;
                        acc.currentType = "text";
                        acc.textContents.put(idx, new StringBuilder());
                        if (!acc.prefixPrinted) {
                            System.out.print(UI.BOLD + UI.GREEN + "\nCline: " + UI.RESET);
                            acc.prefixPrinted = true;
                        } else if (wasThinking) {
                            System.out.print(UI.BOLD + UI.GREEN + "\nCline: " + UI.RESET);
                        }
                        acc.lastWasThinking = false;

                    } else if (block.isToolUse()) {
                        acc.currentType = "tool_use";
                        // .toolUse() returns Optional<ToolUseBlock>
                        block.toolUse().ifPresent(tu -> {
                            acc.toolCalls.add(new ToolCall(idx, tu.id(), tu.name()));
                            acc.toolInputBuilders.put(idx, new StringBuilder());
                        });
                        acc.lastWasThinking = false;
                    }
                });

                // ── Block delta ──────────────────────────────────────────────
                event.contentBlockDelta().ifPresent(cbd -> {
                    var delta = cbd.delta();

                    // .text() → Optional<TextDelta>, TextDelta.text() is the string
                    delta.text().ifPresent(td -> {
                        System.out.print(td.text());
                        System.out.flush();
                        acc.textContents
                           .computeIfAbsent(acc.currentIndex, k -> new StringBuilder())
                           .append(td.text());
                    });

                    // .thinking() → Optional<ThinkingDelta>, ThinkingDelta.thinking() is the string
                    delta.thinking().ifPresent(td ->
                        acc.thinkingTexts
                           .computeIfAbsent(acc.currentIndex, k -> new StringBuilder())
                           .append(td.thinking())
                    );

                    // .signature() → Optional<SignatureDelta>, SignatureDelta.signature() is the string
                    delta.signature().ifPresent(sd ->
                        acc.thinkingSignatures.put(acc.currentIndex, sd.signature())
                    );

                    // .inputJson() → Optional<InputJsonDelta>, InputJsonDelta.partialJson()
                    delta.inputJson().ifPresent(ij ->
                        acc.toolInputBuilders
                           .computeIfAbsent(acc.currentIndex, k -> new StringBuilder())
                           .append(ij.partialJson())
                    );
                });

                // ── Block stop ───────────────────────────────────────────────
                event.contentBlockStop().ifPresent(cbs -> {
                    if ("tool_use".equals(acc.currentType)) {
                        StringBuilder sb = acc.toolInputBuilders.get(acc.currentIndex);
                        acc.toolCalls.stream()
                           .filter(tc -> tc.index == acc.currentIndex)
                           .findFirst()
                           .ifPresent(tc -> tc.inputJson = sb != null ? sb.toString() : "{}");
                    }
                });

                // ── Message delta (stop reason) ───────────────────────────────
                event.messageDelta().ifPresent(md ->
                    // stopReason() returns Optional<StopReason>
                    md.delta().stopReason().ifPresent(sr -> acc.stopReason = sr)
                );
            });
        }

        return acc;
    }

    // ── History reconstruction ────────────────────────────────────────────────

    private List<ContentBlockParam> buildAssistantBlocks(StreamResult acc) {
        List<ContentBlockParam> blocks = new ArrayList<>();

        // Collect all block indices in sorted order to preserve ordering
        Set<Integer> allIndices = new TreeSet<>();
        allIndices.addAll(acc.thinkingTexts.keySet());
        allIndices.addAll(acc.textContents.keySet());
        acc.toolCalls.forEach(tc -> allIndices.add(tc.index));

        for (int idx : allIndices) {
            if (acc.thinkingTexts.containsKey(idx)) {
                blocks.add(ContentBlockParam.ofThinking(
                        ThinkingBlockParam.builder()
                                .thinking(acc.thinkingTexts.get(idx).toString())
                                .signature(acc.thinkingSignatures.getOrDefault(idx, ""))
                                .build()));

            } else if (acc.textContents.containsKey(idx)) {
                blocks.add(ContentBlockParam.ofText(
                        TextBlockParam.builder()
                                .text(acc.textContents.get(idx).toString())
                                .build()));

            } else {
                acc.toolCalls.stream()
                   .filter(tc -> tc.index == idx)
                   .findFirst()
                   .ifPresent(tc -> {
                       // Build ToolUseBlockParam.Input from the parsed JSON map
                       Map<String, Object> inputMap = parseJson(tc.inputJson);
                       var inputBuilder = ToolUseBlockParam.Input.builder();
                       for (Map.Entry<String, Object> e : inputMap.entrySet()) {
                           inputBuilder.putAdditionalProperty(e.getKey(), JsonValue.from(e.getValue()));
                       }
                       blocks.add(ContentBlockParam.ofToolUse(
                               ToolUseBlockParam.builder()
                                       .id(tc.id)
                                       .name(tc.name)
                                       .input(inputBuilder.build())
                                       .build()));
                   });
            }
        }
        return blocks;
    }

    // ── Tool execution ────────────────────────────────────────────────────────

    private String runTool(String name, Map<String, Object> input) {
        try {
            String result = Tools.execute(name, input);
            UI.printToolSuccess(result);
            return result;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            UI.printToolError(msg);
            return "Error: " + msg;
        }
    }

    private boolean confirm(String message) {
        String answer = lineReader.readLine(UI.YELLOW + "\n  ⚠  " + message + " (y/N) " + UI.RESET);
        return answer != null && answer.trim().equalsIgnoreCase("y");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String jsonStr) {
        if (jsonStr == null || jsonStr.isBlank()) return Map.of();
        try {
            return json.readValue(jsonStr, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    /** Mutable accumulator for one streaming response. */
    private static class StreamResult {
        StopReason stopReason = null; // null means end_turn / not set yet

        // Display state
        boolean prefixPrinted = false;
        boolean lastWasThinking = false;
        int currentIndex = -1;
        String currentType = "";

        // Accumulated blocks (keyed by block index)
        final Map<Integer, StringBuilder> thinkingTexts     = new LinkedHashMap<>();
        final Map<Integer, String>        thinkingSignatures = new LinkedHashMap<>();
        final Map<Integer, StringBuilder> textContents       = new LinkedHashMap<>();
        final Map<Integer, StringBuilder> toolInputBuilders  = new LinkedHashMap<>();
        final List<ToolCall>              toolCalls          = new ArrayList<>();
    }

    /** A tool call extracted from the stream. */
    private static class ToolCall {
        final int index;
        final String id;
        final String name;
        String inputJson = "{}";

        ToolCall(int index, String id, String name) {
            this.index = index;
            this.id    = id;
            this.name  = name;
        }
    }
}
