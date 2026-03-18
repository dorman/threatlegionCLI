package com.clinecli;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class Config {

    private static final Path CONFIG_DIR  = Path.of(System.getProperty("user.home"), ".cline");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");
    private static final ObjectMapper JSON = new ObjectMapper();

    private Config() {}

    /**
     * Returns the Anthropic API key by checking in order:
     *  1. ANTHROPIC_API_KEY environment variable
     *  2. ~/.cline/config.json
     * Returns null if not found in either place.
     */
    public static String loadApiKey() {
        String envKey = System.getenv("ANTHROPIC_API_KEY");
        if (envKey != null && !envKey.isBlank()) return envKey;

        if (Files.exists(CONFIG_FILE)) {
            try {
                Map<?, ?> data = JSON.readValue(CONFIG_FILE.toFile(), Map.class);
                Object stored = data.get("anthropic_api_key");
                if (stored instanceof String s && !s.isBlank()) return s;
            } catch (IOException ignored) {}
        }

        return null;
    }

    /**
     * Saves the API key to ~/.cline/config.json.
     */
    public static void saveApiKey(String apiKey) throws IOException {
        Files.createDirectories(CONFIG_DIR);

        Map<String, Object> data = new HashMap<>();
        if (Files.exists(CONFIG_FILE)) {
            try {
                Map<?, ?> existing = JSON.readValue(CONFIG_FILE.toFile(), Map.class);
                existing.forEach((k, v) -> data.put(String.valueOf(k), v));
            } catch (IOException ignored) {}
        }

        data.put("anthropic_api_key", apiKey);
        JSON.writerWithDefaultPrettyPrinter().writeValue(CONFIG_FILE.toFile(), data);
    }
}
