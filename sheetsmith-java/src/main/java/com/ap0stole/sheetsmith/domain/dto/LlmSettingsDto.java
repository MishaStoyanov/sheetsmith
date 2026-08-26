package com.ap0stole.sheetsmith.domain.dto;

import java.util.Map;

public record LlmSettingsDto(
        String providerMode,
        LocalSettings local,
        CloudSettings cloud
) {

    public record LocalSettings(String provider, String baseUrl, String model) {}

    public record CloudSettings(String activeProvider, Map<String, String> apiKeys, Map<String, String> models) {}

    /**
     * The settings a fresh instance starts with, taken from {@code OLLAMA_BASE_URL} and
     * {@code OLLAMA_MODEL} — the two values the README, .env.example and docker-compose all tell a
     * user to set.
     * <p>
     * They used to be hardcoded here, which meant a first run ignored all three: compose would
     * start Ollama, pull the model into it, and the app would then ask a completely different
     * server for a completely different model. Found by actually running
     * {@code docker compose up} rather than reading it.
     */
    /** Only for callers with no configuration to hand — a parse failure recovering mid-request. */
    public static LlmSettingsDto defaults() {
        return defaults("http://localhost:11434", "llama3.1");
    }

    public static LlmSettingsDto defaults(String baseUrl, String model) {
        return new LlmSettingsDto(
                "LOCAL",
                new LocalSettings("OLLAMA", baseUrl, model),
                new CloudSettings("OPENAI", Map.of(), Map.of(
                        "OPENAI", "gpt-4o",
                        "GEMINI", "gemini-2.0-flash",
                        "CLAUDE", "claude-sonnet-4-6",
                        "DEEPSEEK", "deepseek-chat"
                ))
        );
    }
}
