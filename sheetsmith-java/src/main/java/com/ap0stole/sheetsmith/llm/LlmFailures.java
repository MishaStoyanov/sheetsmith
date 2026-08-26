package com.ap0stole.sheetsmith.llm;

import java.util.Locale;

/**
 * Turns a provider's failure into something a user can act on.
 * <p>
 * Raw provider errors are unusable in a UI — a rate-limit refusal from Gemini arrives as a page of
 * JSON — and the most common one here is self-inflicted: a single chat turn makes several calls, so
 * a free tier allowing five a minute is exhausted by one question.
 */
final class LlmFailures {

    private static final int MAX_RAW = 200;

    private LlmFailures() {
    }

    static String humanize(Exception e) {
        String raw = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String probe = raw.toLowerCase(Locale.ROOT);

        if (probe.contains("429") || probe.contains("resource_exhausted")
                || probe.contains("rate limit") || probe.contains("quota")) {
            return "The AI provider turned the request down — its rate limit or quota is used up. "
                    + "One chat turn makes several calls, so a free tier is easy to exhaust: "
                    + "wait a minute, or switch provider in settings.";
        }
        if (probe.contains("401") || probe.contains("403") || probe.contains("unauthorized")
                || probe.contains("api key") || probe.contains("permission_denied")) {
            return "The AI provider rejected the credentials — check the API key in settings.";
        }
        if (probe.contains("connect") || probe.contains("timeout") || probe.contains("timed out")
                || probe.contains("unknownhost") || probe.contains("refused")) {
            return "Could not reach the AI provider. If you are running Ollama locally, check that "
                    + "it is started and that its URL in settings is right.";
        }
        if (probe.contains("404") || probe.contains("model")) {
            return "The AI provider does not recognise the configured model — check the model name in settings.";
        }
        return "AI request failed: " + clip(raw);
    }

    /** Provider errors carry whole JSON documents; only the opening of one is ever informative. */
    private static String clip(String raw) {
        String oneLine = raw.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= MAX_RAW ? oneLine : oneLine.substring(0, MAX_RAW) + "…";
    }
}
