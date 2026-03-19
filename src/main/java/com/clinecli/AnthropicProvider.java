package com.clinecli;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.stream.Collectors;

/**
 * LLMProvider implementation backed by the Anthropic Java SDK.
 * Maintains its own List<MessageParam> history so thinking-block signatures
 * are preserved across turns (required by the Anthropic API).
 */
public class AnthropicProvider implements LLMProvider {

    private static final String DEFAULT_MODEL = "claude-opus-4-6";

    private final AnthropicClient client;
    private final String model;
    private final String systemPrompt;
    private final List<MessageParam> messages = new ArrayList<>();
    private final ObjectMapper json = new ObjectMapper();

    public AnthropicProvider(AnthropicClient client, String model, String systemPrompt) {
        this.client       = client;
        this.model        = model.isBlank() ? DEFAULT_MODEL : model;
        this.systemPrompt = systemPrompt;
    }

    @Override public String getName()    { return "Anthropic"; }
    @Override public String getModel()   { return model; }
    @Override public int historySize()   { return messages.size(); }
    @Override public void clearHistory() { messages.clear(); }

    @Override
    public void addUserMessage(String text) {
        messages.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(text)
                .build());
    }

    @Override
    public void addToolResults(List<ToolResult> results) {
        List<ContentBlockParam> blocks = results.stream()
                .map(r -> ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder()
                                .toolUseId(r.toolUseId)
                                .content(r.content)
                                .build()))
                .collect(Collectors.toList());
        messages.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(blocks)
                .build());
    }

    @Override
    public StreamResult stream(List<ToolDefinition> tools) throws Exception {
        var paramsBuilder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(8096)
                .system(systemPrompt)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .messages(messages);

        for (ToolDefinition td : tools) {
            paramsBuilder.addTool(toAnthropicTool(td));
        }

        Acc acc = new Acc();

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
                            System.out.print(UI.BOLD + UI.GREEN + "\nThreatLegion: " + UI.RESET);
                            acc.prefixPrinted = true;
                        }
                        System.out.print(UI.DIM + "💭 thinking…\n" + UI.RESET);
                        acc.lastWasThinking = true;

                    } else if (block.isText()) {
                        boolean wasThinking = acc.lastWasThinking;
                        acc.currentType = "text";
                        acc.textContents.put(idx, new StringBuilder());
                        if (!acc.prefixPrinted) {
                            System.out.print(UI.BOLD + UI.GREEN + "\nThreatLegion: " + UI.RESET);
                            acc.prefixPrinted = true;
                        } else if (wasThinking) {
                            System.out.print(UI.BOLD + UI.GREEN + "\nThreatLegion: " + UI.RESET);
                        }
                        acc.lastWasThinking = false;

                    } else if (block.isToolUse()) {
                        acc.currentType = "tool_use";
                        block.toolUse().ifPresent(tu -> {
                            acc.pendingToolCalls.add(new PendingTC(idx, tu.id(), tu.name()));
                            acc.toolInputBuilders.put(idx, new StringBuilder());
                        });
                        acc.lastWasThinking = false;
                    }
                });

                // ── Block delta ──────────────────────────────────────────────
                event.contentBlockDelta().ifPresent(cbd -> {
                    var delta = cbd.delta();

                    delta.text().ifPresent(td -> {
                        System.out.print(td.text());
                        System.out.flush();
                        acc.textContents
                           .computeIfAbsent(acc.currentIndex, k -> new StringBuilder())
                           .append(td.text());
                    });

                    delta.thinking().ifPresent(td ->
                        acc.thinkingTexts
                           .computeIfAbsent(acc.currentIndex, k -> new StringBuilder())
                           .append(td.thinking())
                    );

                    delta.signature().ifPresent(sd ->
                        acc.thinkingSignatures.put(acc.currentIndex, sd.signature())
                    );

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
                        acc.pendingToolCalls.stream()
                           .filter(tc -> tc.index == acc.currentIndex)
                           .findFirst()
                           .ifPresent(tc -> tc.inputJson = sb != null ? sb.toString() : "{}");
                    }
                });

                // ── Message delta (stop reason) ───────────────────────────────
                event.messageDelta().ifPresent(md ->
                    md.delta().stopReason().ifPresent(sr -> acc.stopReason = sr)
                );
            });
        }

        // Persist the assistant turn (including thinking blocks) into Anthropic history
        messages.add(MessageParam.builder()
                .role(MessageParam.Role.ASSISTANT)
                .contentOfBlockParams(buildAssistantBlocks(acc))
                .build());

        // Build the provider-agnostic StreamResult
        StreamResult result = new StreamResult();
        result.isToolUse = acc.stopReason != null && StopReason.TOOL_USE.equals(acc.stopReason);
        result.text = acc.textContents.values().stream()
                .map(StringBuilder::toString)
                .collect(Collectors.joining());
        result.toolCalls = acc.pendingToolCalls.stream()
                .map(tc -> new ToolCall(tc.id, tc.name, tc.inputJson))
                .collect(Collectors.toList());
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Tool toAnthropicTool(ToolDefinition td) {
        var propsBuilder = Tool.InputSchema.Properties.builder();
        for (Map.Entry<String, Object> e : td.properties.entrySet()) {
            propsBuilder.putAdditionalProperty(e.getKey(), JsonValue.from(e.getValue()));
        }
        return Tool.builder()
                .name(td.name)
                .description(td.description)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(propsBuilder.build())
                        .required(td.required)
                        .build())
                .build();
    }

    private List<ContentBlockParam> buildAssistantBlocks(Acc acc) {
        List<ContentBlockParam> blocks = new ArrayList<>();

        Set<Integer> allIndices = new TreeSet<>();
        allIndices.addAll(acc.thinkingTexts.keySet());
        allIndices.addAll(acc.textContents.keySet());
        acc.pendingToolCalls.forEach(tc -> allIndices.add(tc.index));

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
                acc.pendingToolCalls.stream()
                   .filter(tc -> tc.index == idx)
                   .findFirst()
                   .ifPresent(tc -> {
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

    private static class Acc {
        StopReason stopReason = null;
        boolean prefixPrinted  = false;
        boolean lastWasThinking = false;
        int     currentIndex   = -1;
        String  currentType    = "";

        final Map<Integer, StringBuilder> thinkingTexts      = new LinkedHashMap<>();
        final Map<Integer, String>        thinkingSignatures  = new LinkedHashMap<>();
        final Map<Integer, StringBuilder> textContents        = new LinkedHashMap<>();
        final Map<Integer, StringBuilder> toolInputBuilders   = new LinkedHashMap<>();
        final List<PendingTC>             pendingToolCalls    = new ArrayList<>();
    }

    private static class PendingTC {
        final int index;
        final String id;
        final String name;
        String inputJson = "{}";

        PendingTC(int index, String id, String name) {
            this.index = index;
            this.id    = id;
            this.name  = name;
        }
    }
}
