package com.clinecli;

import java.util.List;
import java.util.Map;

public class ToolDefinition {
    public String name;
    public String description;
    public Map<String, Object> properties; // JSON Schema properties map
    public List<String> required;
}
