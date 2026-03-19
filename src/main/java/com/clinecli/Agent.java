package com.clinecli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jline.reader.LineReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Orchestrates the agentic loop: calls the active LLMProvider, executes tool
 * calls (with approval for destructive ones), feeds results back, and repeats
 * until the model stops requesting tools.
 *
 * Ethics layers 1–3 live here. Layer 4 is inside Tools.runCommand().
 * Layer 5 (scope confirmation) is enforced in Main before Agent is created.
 */
public class Agent {

    // Loaded once at startup; empty if POLICY.md not found
    private static final String POLICY_TEXT = loadPolicy();

    /** Build the system prompt that is passed to every LLMProvider. */
    public static String buildSystemPrompt(String authorizedScope) {
        return """
                You are ThreatLegion, an expert security analyst and vulnerability scanner \
                running in the terminal. Your purpose is strictly defensive: you help users \
                identify, understand, and remediate security vulnerabilities in codebases they \
                are authorized to assess.

                IDENTITY & MISSION
                - You are a defensive security tool, not an offensive one.
                - Every action you take must serve the goal of finding and fixing vulnerabilities.
                - You operate only within the authorized scope confirmed at session start.

                AUTHORIZED SCOPE FOR THIS SESSION
                %s

                ETHICAL CONSTRAINTS — NON-NEGOTIABLE
                You must never, under any circumstances:
                1. Develop working exploits or attack payloads for use against live systems
                2. Perform network attacks, port scans, or denial-of-service testing
                3. Extract, log, or exfiltrate credentials, API keys, or secrets found in code
                4. Send data to external servers or endpoints
                5. Write malware, reverse shells, backdoors, or any offensive code
                6. Operate outside the authorized scope stated above
                7. Install persistence mechanisms or modify system startup configuration
                8. Attempt privilege escalation beyond what reading the codebase requires

                If a request would violate any constraint above, refuse clearly and explain why.
                If a request is ambiguous, choose the defensive interpretation and confirm.

                OPERATIONAL GUIDELINES
                - Be concise. Briefly explain what you are about to do, then do it.
                - Prefer read_file and search_files for discovery; use write_file / edit_file \
                  only to apply a security fix.
                - When you find a vulnerability, report: location, severity, description, \
                  and recommended fix.
                - If a task spans multiple steps, outline them first.
                - When you encounter errors, diagnose the root cause before trying fixes.

                LOADED POLICY
                %s

                Current working directory: %s
                """.formatted(
                    authorizedScope.isBlank()
                        ? "(no scope specified — restrict to the current directory)"
                        : authorizedScope,
                    POLICY_TEXT.isBlank() ? "(policy file not found)" : POLICY_TEXT,
                    System.getProperty("user.dir"));
    }

    /** Layer 3: pre-flight keyword blocklist. */
    private static final List<String> BLOCKED_PHRASES = List.of(
            "exploit", "payload", "reverse shell", "bind shell", "backdoor",
            "keylogger", "ransomware", "exfiltrate", "c2 ", "command and control",
            "denial of service", "dos attack", "ddos", "port scan all",
            "upload to", "send to server", "post to http", "curl http", "wget http"
    );

    private final LLMProvider provider;
    private final LineReader lineReader;
    private final ObjectMapper json = new ObjectMapper();

    public Agent(LLMProvider provider, LineReader lineReader) {
        this.provider   = provider;
        this.lineReader = lineReader;
    }

    public void clearHistory()  { provider.clearHistory(); }
    public int  historySize()   { return provider.historySize(); }

    /**
     * Run one user turn. Adds the user message to the provider's history,
     * runs the agentic loop, and handles all tool execution.
     */
    public void runTurn(String userText) throws Exception {
        // Layer 3: pre-flight check before anything hits the model
        String blocked = preflightCheck(userText);
        if (blocked != null) {
            System.out.println(UI.RED + "\n  ✗ " + blocked + UI.RESET);
            return;
        }

        provider.addUserMessage(userText);

        while (true) {
            StreamResult result = provider.stream(Tools.getToolDefinitions());

            if (!result.isToolUse) {
                System.out.println();
                break;
            }

            // Execute each tool call and collect results
            List<ToolResult> toolResults = new ArrayList<>();
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

                toolResults.add(new ToolResult(tc.id, resultText));
            }

            provider.addToolResults(toolResults);
        }
    }

    // ── Ethics layer 3: pre-flight ────────────────────────────────────────────

    private String preflightCheck(String userText) {
        String lower = userText.toLowerCase();
        for (String phrase : BLOCKED_PHRASES) {
            if (lower.contains(phrase)) {
                return "Request blocked by ethical policy: detected prohibited pattern \""
                        + phrase.strip() + "\". ThreatLegion performs defensive analysis only.";
            }
        }
        return null;
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

    // ── Policy loading ────────────────────────────────────────────────────────

    private static String loadPolicy() {
        Path[] candidates = {
            Path.of(System.getProperty("user.dir"), "POLICY.md"),
            Path.of(System.getProperty("user.home"), ".cline", "POLICY.md")
        };
        for (Path p : candidates) {
            if (Files.exists(p)) {
                try {
                    return Files.readString(p);
                } catch (IOException ignored) {}
            }
        }
        return "";
    }
}
