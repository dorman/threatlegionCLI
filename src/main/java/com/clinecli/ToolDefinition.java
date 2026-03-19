package com.clinecli;

import java.util.List;
import java.util.Map;

/** Provider-agnostic tool definition (name, description, JSON Schema properties). */
public class ToolDefinition {
    public final String name;
    public final String description;
    public final Map<String, Object> properties; // JSON Schema properties map
    public final List<String> required;

    public ToolDefinition(String name, String description,
                          Map<String, Object> properties, List<String> required) {
        this.name        = name;
        this.description = description;
        this.properties  = properties;
        this.required    = required;
    }
}
