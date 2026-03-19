package com.clinecli;

import java.util.ArrayList;
import java.util.List;

/** Result returned by LLMProvider.stream() after one model turn. */
public class StreamResult {
    /** True when stop reason was tool_use — the agentic loop should continue. */
    public boolean isToolUse = false;

    /** Full accumulated text response for this turn. */
    public String text = "";

    /** Tool calls the model wants to make (populated when isToolUse is true). */
    public List<ToolCall> toolCalls = new ArrayList<>();
}
