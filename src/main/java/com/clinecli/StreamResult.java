package com.clinecli;

import java.util.ArrayList;
import java.util.List;

public class StreamResult {
    public boolean isTooUse = false; // true if stop reason was tool_use
    public String text = ""; // full accumulated text response
    public List<ToolCall> toolCalls = new ArrayList<>();
}
