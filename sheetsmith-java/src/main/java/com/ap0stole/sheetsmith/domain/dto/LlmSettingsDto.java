package com.ap0stole.sheetsmith.domain.dto;

import java.util.Map;
import java.util.Set;

/**
 * Which model this instance calls, and how.
 *
 * @param providerMode LOCAL or CLOUD — which of the two blocks below is in force
 * @param local        the Ollama server on this machine or network
 * @param cloud        the vendor to call instead, and the key to call it with
 */
public record LlmSettingsDto(
        String providerMode,
        LocalSettings local,
        CloudSettings cloud
) {

    /**
     * A model on your own machine, which is the default and the reason this application exists.
     *
     * @param provider only OLLAMA today
     * @param baseUrl  where it listens. In Docker this is host.docker.internal rather than
     *                 localhost, which a container cannot reach the host by
     * @param model    the tag to ask for, as Ollama lists it
     */
    public record LocalSettings(String provider, String baseUrl, String model) {}

    /**
     * @param apiKeys   the keys themselves. Sent <em>to</em> the server when somebody types one;
     *                  never sent back — see {@code savedKeys}
     * @param savedKeys which providers already have a key stored, which is all a screen needs to
     *                  say "this one is set". Echoing the key back would put every stored secret
     *                  into a browser, a proxy log and a devtools tab on each visit to the settings
     *                  panel, for a value nobody reads off the screen anyway
     */
    public record CloudSettings(String activeProvider, Map<String, String> apiKeys,
                                Map<String, String> models, Set<String> savedKeys) {

        public CloudSettings(String activeProvider, Map<String, String> apiKeys, Map<String, String> models) {
            this(activeProvider, apiKeys, models, Set.of());
        }
    }

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
