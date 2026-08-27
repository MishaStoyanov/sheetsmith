package com.ap0stole.sheetsmith.domain.dto.price;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a catalogue would change, offered for confirmation rather than applied.
 *
 * @param source     the host that was contacted, named so an outbound request is never a surprise
 * @param proposals  one row per model, including the ones that need no change — a refresh that
 *                   listed only differences would leave somebody wondering whether the rest were
 *                   checked or skipped
 */
public record PriceProposalDto(String source, List<Proposal> proposals) {

    /**
     * @param status    what this row would do
     * @param catalogueModel the name the catalogue used, kept when it differs from the recorded one
     *                       so a matched-by-prefix row can be checked by eye before it is accepted
     */
    public record Proposal(String provider, String model, String catalogueModel,
                           BigDecimal currentInputPerMillion, BigDecimal currentOutputPerMillion,
                           BigDecimal proposedInputPerMillion, BigDecimal proposedOutputPerMillion,
                           long usedByCalls, Status status) {
    }

    public enum Status {
        /** Used here, never priced. The row the analytics screen is complaining about. */
        NEW,
        /** Priced already, and the catalogue disagrees. */
        CHANGED,
        /** Priced already, and the catalogue agrees. Shown so it is visibly checked. */
        UNCHANGED,
        /** Used or priced here, and the catalogue has never heard of it. */
        NOT_IN_CATALOGUE
    }
}
