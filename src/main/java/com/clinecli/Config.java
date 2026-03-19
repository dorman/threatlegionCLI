package com.clinecli;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Persists configuration to ~/.cline/config.json.
 *
 * Stored fields:
 *   anthropic_api_key  – legacy key kept for backward compat
 *   last_provider      – name of the last-used provider (e.g. "anthropic")
 *   keys               – map of provider name -> api key
 *   models             – map of provider name -> model id
 */
public final class Config {

    private static final Path CONFIG_DIR  = Path.of(System.getProperty("user.home"), ".cline");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");
    private static final ObjectMapper JSON = new ObjectMapper();

    private Config() {}

    // ── Legacy: Anthropic-only key (backward compat) ──────────────────────────

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

    public static void saveApiKey(String apiKey) throws IOException {
        Map<String, Object> data = load();
        data.put("anthropic_api_key", apiKey);
        // Also store under the generic keys map
        getOrCreateMap(data, "keys").put("anthropic", apiKey);
        save(data);
    }

    // ── Multi-provider key storage ────────────────────────────────────────────

    /** Returns the stored API key for the given provider name, or null. */
    public static String loadProviderKey(String providerName) {
        // Check environment variable first: e.g. OPENAI_API_KEY, OPENROUTER_API_KEY
        String envName = providerName.toUpperCase().replace(" ", "_").replace("-", "_") + "_API_KEY";
        String envKey = System.getenv(envName);
        if (envKey != null && !envKey.isBlank()) return envKey;

        Map<String, Object> data = load();
        Map<String, Object> keys = getOrCreateMap(data, "keys");
        Object v = keys.get(providerName);
        return (v instanceof String s && !s.isBlank()) ? s : null;
    }

    /** Saves the API key for a provider. */
    public static void saveProviderKey(String providerName, String apiKey) throws IOException {
        Map<String, Object> data = load();
        getOrCreateMap(data, "keys").put(providerName, apiKey);
        // Mirror Anthropic key to legacy field for backward compat
        if ("anthropic".equals(providerName)) {
            data.put("anthropic_api_key", apiKey);
        }
        save(data);
    }

    // ── Model storage ─────────────────────────────────────────────────────────

    /** Returns the last-saved model for a provider, or the given default. */
    public static String loadModel(String providerName, String defaultModel) {
        Map<String, Object> data = load();
        Map<String, Object> models = getOrCreateMap(data, "models");
        Object v = models.get(providerName);
        return (v instanceof String s && !s.isBlank()) ? s : defaultModel;
    }

    /** Saves the model choice for a provider. */
    public static void saveModel(String providerName, String model) throws IOException {
        Map<String, Object> data = load();
        getOrCreateMap(data, "models").put(providerName, model);
        save(data);
    }

    // ── Last-used provider ────────────────────────────────────────────────────

    public static String loadLastProvider() {
        Map<String, Object> data = load();
        Object v = data.get("last_provider");
        return (v instanceof String s && !s.isBlank()) ? s : null;
    }

    public static void saveLastProvider(String providerName) throws IOException {
        Map<String, Object> data = load();
        data.put("last_provider", providerName);
        save(data);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load() {
        if (Files.exists(CONFIG_FILE)) {
            try {
                Map<?, ?> raw = JSON.readValue(CONFIG_FILE.toFile(), Map.class);
                Map<String, Object> result = new HashMap<>();
                raw.forEach((k, v) -> result.put(String.valueOf(k), v));
                return result;
            } catch (IOException ignored) {}
        }
        return new HashMap<>();
    }

    private static void save(Map<String, Object> data) throws IOException {
        Files.createDirectories(CONFIG_DIR);
        JSON.writerWithDefaultPrettyPrinter().writeValue(CONFIG_FILE.toFile(), data);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getOrCreateMap(Map<String, Object> parent, String key) {
        Object v = parent.get(key);
        if (v instanceof Map) return (Map<String, Object>) v;
        Map<String, Object> m = new HashMap<>();
        parent.put(key, m);
        return m;
    }
}
