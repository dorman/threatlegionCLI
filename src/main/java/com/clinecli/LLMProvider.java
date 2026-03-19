package com.clinecli;

import java.util.List;

/**
 * Abstraction over any LLM backend. Each provider is stateful — it owns its
 * own conversation history in whatever native format the API requires.
 */
public interface LLMProvider {

    /** Display name, e.g. "Anthropic", "OpenAI", "OpenRouter" */
    String getName();

    /** Active model ID, e.g. "claude-opus-4-6", "gpt-4o" */
    String getModel();

    /** Append a user text message to the internal history. */
    void addUserMessage(String text);

    /** Append tool results (returned after tool execution) to the internal history. */
    void addToolResults(List<ToolResult> results);

    /**
     * Stream the next model response.
     * The provider must append the assistant turn to its internal history before returning.
     *
     * @param tools Tool definitions to expose to the model
     * @return Accumulated result: text, tool calls, and whether more tool calls are expected
     */
    StreamResult stream(List<ToolDefinition> tools) throws Exception;

    /** Clear all conversation history (keeps the system prompt if applicable). */
    void clearHistory();

    /** Number of messages in the current history. */
    int historySize();
}
