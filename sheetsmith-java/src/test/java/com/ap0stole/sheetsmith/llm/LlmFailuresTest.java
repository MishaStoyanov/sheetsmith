package com.ap0stole.sheetsmith.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider errors reach the user verbatim, so the one they will actually hit — a free tier's
 * rate limit, exhausted by a single multi-step turn — must not arrive as a page of JSON.
 */
class LlmFailuresTest {

    private static final String GEMINI_429 = """
            429 - [{ "error": { "code": 429, "message": "You exceeded your current quota, please check
            your plan and billing details. Quota exceeded for metric:
            generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 5,
            model: gemini-2.5-flash. Please retry in 44.470077695s.", "status": "RESOURCE_EXHAUSTED" }}]
            """;

    @Test
    @DisplayName("a rate limit is explained, not dumped")
    void explainsARateLimit() {
        String message = LlmFailures.humanize(new RuntimeException(GEMINI_429));

        assertThat(message)
                .contains("rate limit or quota").contains("switch provider in settings")
                .doesNotContain("{").doesNotContain("RESOURCE_EXHAUSTED");
        assertThat(message.length()).isLessThan(300);
    }

    @Test
    @DisplayName("a bad key points at settings rather than at the provider's wording")
    void explainsABadKey() {
        assertThat(LlmFailures.humanize(new RuntimeException("401 Unauthorized: invalid api key")))
                .contains("API key in settings");
    }

    @Test
    @DisplayName("an unreachable local model names the likely cause")
    void explainsAnUnreachableProvider() {
        assertThat(LlmFailures.humanize(new IOException("Connection refused: localhost/127.0.0.1:11434")))
                .contains("Ollama");
    }

    @Test
    @DisplayName("anything else is passed through, clipped to one readable line")
    void clipsAnythingElse() {
        String noisy = "boom\n".repeat(200);

        String message = LlmFailures.humanize(new RuntimeException(noisy));

        assertThat(message)
                .startsWith("AI request failed: boom boom")
                .endsWith("…");
        assertThat(message.length()).isLessThan(250);
    }

    @Test
    @DisplayName("a null message does not become the word null")
    void survivesANullMessage() {
        assertThat(LlmFailures.humanize(new IllegalStateException())).doesNotContain("null");
    }
}
