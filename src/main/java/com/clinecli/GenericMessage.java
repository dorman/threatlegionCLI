package com.clinecli;

import java.util.List;

public class GenericMessage {
    public enum Role { USER, ASSISTANT }

    public Role role;
    public String text; // plain text context (user messages)
    public List<ToolCall> toolCalls; // set when assistant used tools
    public List<ToolResult> toolResults; // set when user is returning tool results
}

