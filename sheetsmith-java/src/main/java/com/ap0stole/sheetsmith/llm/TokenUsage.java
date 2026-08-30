package com.ap0stole.sheetsmith.llm;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * What one call to the model cost, kept apart because providers price reading and writing
 * differently and a single total cannot answer "why was that expensive".
 * <p>
 * Every field is nullable and null means <em>not reported</em>, never zero: a local model often
 * bills nothing and says nothing, and recording that as a run costing 0 tokens would be a made-up
 * number sitting in an audit.
 */
public record TokenUsage(Long promptTokens, Long completionTokens, Long totalTokens) {

    public static final TokenUsage NONE = new TokenUsage(null, null, null);

    /**
     * Spring AI hands back {@code EmptyUsage} — three zeros, not nulls — when a provider reports
     * nothing, so an all-zero reading is read as silence rather than as a free run.
     */
    public static TokenUsage from(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return NONE;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            return NONE;
        }

        Long prompt = toLong(usage.getPromptTokens());
        Long completion = toLong(usage.getCompletionTokens());
        Long total = toLong(usage.getTotalTokens());
        if (prompt == null && completion == null && total == null) {
            return NONE;
        }

        // A provider that reports the two halves but no total is common enough to be worth adding
        // up here rather than leaving a hole in the column the UI sorts on. No need to ask whether
        // either half is present: the guard above returned when all three were missing.
        if (total == null) {
            total = zeroIfNull(prompt) + zeroIfNull(completion);
        }
        return new TokenUsage(prompt, completion, total);
    }

    /** Adds a second call's cost to this one; a run plans once and may repair once. */
    public TokenUsage plus(TokenUsage other) {
        if (other == null || other.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return other;
        }
        return new TokenUsage(
                sum(promptTokens, other.promptTokens),
                sum(completionTokens, other.completionTokens),
                sum(totalTokens, other.totalTokens));
    }

    public boolean isEmpty() {
        return promptTokens == null && completionTokens == null && totalTokens == null;
    }

    private static Long sum(Long a, Long b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + b;
    }

    private static Long toLong(Integer value) {
        return (value == null || value == 0) ? null : value.longValue();
    }

    private static long zeroIfNull(Long value) {
        return value == null ? 0L : value;
    }
}
