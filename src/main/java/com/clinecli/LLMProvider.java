package com.clinecli;

import java.util.List;
import java.util.Map;

public interface LLMProvider {
    /**
     * Display name shown in the welcome message, e.g. "Anthropic", "OpenAI"
     */
    String getName();

    /**
     * The model currently in use, e.g. "claude-opus-4-6", "gpt-4o"
     */
    String getModel();

    /**
     * Stream one response turn from the LLM.
     *
     * @param messages Full conversation history (yourGenericMessage type)
     * @param tools    Tool definitions to expose to the model
     * @return Accumulated result: text, tool calls, stop reason
     */
    StreamResult stream(List<GenericMessage> messages, List<ToolDefinition> tools) throws Exception;
}



