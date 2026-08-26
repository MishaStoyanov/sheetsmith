package com.ap0stole.sheetsmith.services.excel;

/**
 * Which tense a step's description is written in. The same sentence serves two surfaces with
 * opposite meanings — a plan card proposes a step that has not run yet, while the chat chain and
 * job history record one that already did.
 */
public enum StepTense {

    /** "Sort the data" — a step being proposed for approval. */
    IMPERATIVE,

    /** "Sorted the data" — a step that already ran. */
    PAST
}
