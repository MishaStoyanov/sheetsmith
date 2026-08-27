package com.ap0stole.sheetsmith.domain.dto.analytics;

import java.math.BigDecimal;
import java.util.List;

/**
 * Everything the analytics screen shows, in one answer.
 * <p>
 * One response rather than five endpoints, and not to save round trips: every chart on the screen
 * is drawn from the same filters, and five answers can disagree with each other if a call lands
 * between the second and the third.
 *
 * @param costKnown       whether any of this can be expressed in money at all — false when no price
 *                        has been entered, which is how the instance starts
 * @param unpricedModels  models that were used and have no price, so the screen can name what is
 *                        missing instead of quietly reporting a smaller total
 */
public record AnalyticsSummaryDto(
        Totals totals,
        List<Slice> byProvider,
        List<Slice> byModel,
        List<UserSlice> byUser,
        List<Bucket> overTime,
        boolean costKnown,
        List<String> unpricedModels) {

    /**
     * @param cost null when nothing used could be priced; a partial sum is never reported as if it
     *             were the whole, so the screen can say which models are missing
     */
    public record Totals(long calls, long promptTokens, long completionTokens, long totalTokens,
                         BigDecimal cost, long documents, long runs) {
    }

    public record Slice(String label, long calls, long totalTokens, BigDecimal cost) {
    }

    /** @param userId null for the calls nobody owns — shown as such rather than dropped */
    public record UserSlice(Long userId, String name, long calls, long totalTokens, BigDecimal cost) {
    }

    public record Bucket(String label, long calls, long totalTokens, BigDecimal cost) {
    }
}
