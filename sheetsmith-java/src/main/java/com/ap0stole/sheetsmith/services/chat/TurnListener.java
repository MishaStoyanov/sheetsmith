package com.ap0stole.sheetsmith.services.chat;

/**
 * Watches a chat turn while it runs. A turn can take a minute against a local model, and the tool
 * calls are the only visible sign it is doing anything — this is how a caller streams them out
 * instead of waiting for the whole result.
 * <p>
 * Purely observational: it can neither change what the turn does nor stop it.
 */
@FunctionalInterface
public interface TurnListener {

    /** Nobody is watching. The synchronous path passes this, so the loop needs no null checks. */
    TurnListener NOOP = (invocation, order) -> { };

    /**
     * One tool call has just finished — successfully or not.
     *
     * @param order strictly chronological, 0-based. The persisted chain may differ by one position:
     *              it files the self-check ahead of the repair calls it triggered, which cannot be
     *              known until they have run.
     */
    void onStep(ToolInvocation invocation, int order);
}
