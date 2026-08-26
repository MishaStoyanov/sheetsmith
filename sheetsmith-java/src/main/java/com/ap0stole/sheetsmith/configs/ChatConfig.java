package com.ap0stole.sheetsmith.configs;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Budgets for the chat agent loop. They exist to keep a single turn bounded: the model may
 * only take so many steps, and each tool result must stay small enough that the conversation
 * never turns into "here is the whole spreadsheet".
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "sheetsmith.chat")
public class ChatConfig {

    /** Maximum tool invocations the agent may make before it must answer. */
    /**
     * Whether this instance has a chat at all.
     * <p>
     * Off, the deployment is the improve flow alone and the only thing that reaches the model is the
     * sheet's structure — names, headers, ranges and existing formulas. The chat's message endpoints,
     * its agent, its read-only query tools and the "what would you improve?" inspection are all
     * absent from the application context rather than hidden, and the formula labels the planner
     * normally sends are dropped too, since those are read out of cells.
     * <p>
     * It exists for deployments where that guarantee has to be checkable rather than trusted.
     */
    private boolean enabled = true;

    private int maxSteps = 8;

    /** Hard cap on cells a single READ_RANGE may return. */
    private int maxCells = 300;

    /** Hard cap on rows any query tool may return. */
    private int maxRows = 50;

    /** How many previous messages of the conversation are replayed to the model. */
    private int historyMessages = 12;

    /** Extra steps granted to fix formula errors the turn's own edit introduced. 0 disables the check. */
    private int repairSteps = 2;

    /**
     * Send the full action rules from the first step instead of escalating to them on demand.
     * <p>
     * The two-tier catalog trades prompt size against the KV prefix cache, and which way that trade
     * pays depends on who is serving the model. A cloud provider bills per token, so the compact
     * index wins. A local one re-reads the prompt each step but caches the prefix, so swapping the
     * system prompt mid-turn throws that cache away and re-processes everything — and the swap also
     * costs an extra round trip, because the step that triggered it has to be asked again.
     * <p>
     * Off by default, which keeps today's behaviour. Turn it on to measure the local case.
     */
    private boolean fullCatalogAlways = false;

    /**
     * How long the streaming endpoint keeps the connection open. Generous on purpose: a slow local
     * model spending the whole step budget can legitimately run for minutes, and a timeout here
     * kills a turn the user is still waiting for.
     */
    private long streamTimeoutMs = 600_000;
}
