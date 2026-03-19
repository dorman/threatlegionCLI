package com.clinecli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * LLMProvider implementation for any OpenAI-compatible API:
 * OpenAI, OpenRouter, Together AI, Groq, local Ollama, etc.
 *
 * Uses OkHttp + SSE parsing directly — no SDK required beyond what is
 * already on the classpath as a transitive dependency of anthropic-java.
 */
public class OpenAICompatibleProvider implements LLMProvider {

    private final String name;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String systemPrompt;

    /** Conversation history in OpenAI chat format. */
    private final List<Map<String, Object>> messages = new ArrayList<>();

    private final ObjectMapper json = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .readTimeout(120, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .build();

    public OpenAICompatibleProvider(String name, String baseUrl, String apiKey,
                                     String model, String systemPrompt) {
        this.name         = name;
        this.baseUrl      = baseUrl.replaceAll("/+$", ""); // strip trailing slash
        this.apiKey       = apiKey;
        this.model        = model;
        this.systemPrompt = systemPrompt;
        // System prompt as first message
        messages.add(Map.of("role", "system", "content", systemPrompt));
    }

    @Override public String getName()  { return name; }
    @Override public String getModel() { return model; }
    @Override public int historySize() { return messages.size(); }

    @Override
    public void clearHistory() {
        messages.clear();
        messages.add(Map.of("role", "system", "content", systemPrompt));
    }

    @Override
    public void addUserMessage(String text) {
        messages.add(Map.of("role", "user", "content", text));
    }

    @Override
    public void addToolResults(List<ToolResult> results) {
        for (ToolResult r : results) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", "tool");
            msg.put("tool_call_id", r.toolUseId);
            msg.put("content", r.content);
            messages.add(msg);
        }
    }

    @Override
    public StreamResult stream(List<ToolDefinition> tools) throws Exception {
        // ── Build request body ────────────────────────────────────────────────
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", true);

        if (!tools.isEmpty()) {
            List<Map<String, Object>> toolList = tools.stream()
                    .map(this::toOpenAITool)
                    .toList();
            body.put("tools", toolList);
        }

        String bodyJson = json.writeValueAsString(body);

        Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .post(RequestBody.create(bodyJson, MediaType.parse("application/json")))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .build();

        // ── Stream SSE ────────────────────────────────────────────────────────
        StreamResult result = new StreamResult();
        StringBuilder fullText = new StringBuilder();
        // keyed by tool-call index from the stream
        Map<Integer, Map<String, Object>> tcBuilders = new LinkedHashMap<>();
        boolean prefixPrinted = false;

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errBody = response.body() != null ? response.body().string() : "(no body)";
                throw new RuntimeException("API error " + response.code() + ": " + errBody);
            }

            ResponseBody responseBody = response.body();
            if (responseBody == null) throw new RuntimeException("Empty response body");

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(responseBody.byteStream()))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data: ")) continue;
                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) break;
                    if (data.isEmpty()) continue;

                    JsonNode chunk;
                    try {
                        chunk = json.readTree(data);
                    } catch (Exception e) {
                        continue; // malformed chunk — skip
                    }

                    JsonNode choices = chunk.get("choices");
                    if (choices == null || choices.isEmpty()) continue;

                    JsonNode choice = choices.get(0);
                    JsonNode delta  = choice.get("delta");
                    if (delta == null) continue;

                    // ── Text content ─────────────────────────────────────────
                    JsonNode content = delta.get("content");
                    if (content != null && !content.isNull()) {
                        String text = content.asText();
                        if (!text.isEmpty()) {
                            if (!prefixPrinted) {
                                System.out.print(UI.BOLD + UI.GREEN + "\nThreatLegion: " + UI.RESET);
                                prefixPrinted = true;
                            }
                            System.out.print(text);
                            System.out.flush();
                            fullText.append(text);
                        }
                    }

                    // ── Tool calls (streamed incrementally) ──────────────────
                    JsonNode toolCalls = delta.get("tool_calls");
                    if (toolCalls != null && toolCalls.isArray()) {
                        for (JsonNode tcDelta : toolCalls) {
                            int idx = tcDelta.has("index") ? tcDelta.get("index").asInt() : 0;

                            Map<String, Object> tc = tcBuilders.computeIfAbsent(idx, k -> {
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("arguments", new StringBuilder());
                                return m;
                            });

                            if (tcDelta.has("id") && !tcDelta.get("id").isNull()) {
                                tc.put("id", tcDelta.get("id").asText());
                            }
                            JsonNode fn = tcDelta.get("function");
                            if (fn != null) {
                                if (fn.has("name") && !fn.get("name").isNull()) {
                                    tc.put("name", fn.get("name").asText());
                                }
                                if (fn.has("arguments")) {
                                    ((StringBuilder) tc.get("arguments"))
                                            .append(fn.get("arguments").asText());
                                }
                            }
                        }
                    }

                    // ── Finish reason ─────────────────────────────────────────
                    JsonNode finishReason = choice.get("finish_reason");
                    if (finishReason != null && !finishReason.isNull()) {
                        result.isToolUse = "tool_calls".equals(finishReason.asText());
                    }
                }
            }
        }

        result.text = fullText.toString();

        // Materialise tool calls from builders
        for (Map<String, Object> tc : tcBuilders.values()) {
            String id   = (String) tc.getOrDefault("id", "call_" + UUID.randomUUID());
            String tcName = (String) tc.getOrDefault("name", "unknown");
            String args = tc.get("arguments").toString();
            result.toolCalls.add(new ToolCall(id, tcName, args));
        }

        // ── Persist assistant turn to history ─────────────────────────────────
        Map<String, Object> assistantMsg = new LinkedHashMap<>();
        assistantMsg.put("role", "assistant");

        if (!result.toolCalls.isEmpty()) {
            List<Map<String, Object>> tcList = new ArrayList<>();
            for (ToolCall tc : result.toolCalls) {
                tcList.add(Map.of(
                        "id",       tc.id,
                        "type",     "function",
                        "function", Map.of("name", tc.name, "arguments", tc.inputJson)
                ));
            }
            assistantMsg.put("tool_calls", tcList);
            // content may be null or empty when tool_calls is present — that's valid
            if (!result.text.isEmpty()) {
                assistantMsg.put("content", result.text);
            } else {
                assistantMsg.put("content", null);
            }
        } else {
            assistantMsg.put("content", result.text);
        }

        messages.add(assistantMsg);
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> toOpenAITool(ToolDefinition td) {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name",        td.name,
                        "description", td.description,
                        "parameters",  Map.of(
                                "type",       "object",
                                "properties", td.properties,
                                "required",   td.required
                        )
                )
        );
    }
}
