package com.ap0stole.sheetsmith.llm;

/**
 * One reply from the chat model, and what it cost.
 * <p>
 * The decision and the price travel together because the caller has to record the second and act on
 * the first, and reading the cost anywhere else would mean asking the provider twice.
 */
public record ChatCall(AgentDecision decision, TokenUsage usage, LlmEngine engine) {
}
