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
 * @param pricesCheckedAt when the least recently confirmed price was last looked at, or null where
 *                        there are none. Carried so the money view can say its figures rest on
 *                        something old — the person reading a spend chart is exactly who should
 *                        know that, and they would otherwise have to go and check the price list
 * @param neverUsed       nothing has ever been recorded here, filters ignored. Answered by the
 *                        server because the screen cannot work it out: an empty result under a date
 *                        range means either "nothing yet" or "nothing in these dates", and telling
 *                        somebody to widen a range they have not set is the wrong half of that
 * @param unpricedModels  models that were used and have no price, so the screen can name what is
 *                        missing instead of quietly reporting a smaller total
 */
public record AnalyticsSummaryDto(
        Totals totals,
        List<Slice> byProvider,
        List<Slice> byModel,
        List<UserSlice> byUser,
        List<Bucket> overTime,
        List<UserBucket> overTimeByUser,
        Runs runs,
        java.time.LocalDateTime pricesCheckedAt,
        boolean neverUsed,
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

    /**
     * One person's share, carrying enough to stand on its own as a panel.
     *
     * @param userId    null for the calls nobody owns — shown as such rather than dropped
     * @param documents documents worked on, counted as sessions: the same file opened twice is two
     *                  pieces of work, and two different reports both called report.xlsx are two
     *                  documents. Whichever is chosen the screen has to say which, and this one is
     *                  countable and means something.
     */
    public record UserSlice(Long userId, String name, long calls, long totalTokens, BigDecimal cost,
                            long documents) {
    }

    /**
     * One slice of a total.
     *
     * @param label       what the slice is: a provider, a model, or a date at the chosen
     *                    granularity
     * @param calls       how many calls fell in it
     * @param totalTokens prompt and answer together
     * @param cost        what they cost, or null where nothing in the slice had a price — null
     *                    rather than zero, because "not priced" and "free" are different facts and
     *                    only one of them is worth acting on
     */
    public record Bucket(String label, long calls, long totalTokens, BigDecimal cost) {
    }

    /**
     * How the runs themselves went, as opposed to what they asked of a model.
     *
     * @param successRate    completed out of the runs that reached a verdict, or null when none
     *                       have. A run still in flight is not a failure and is not counted as one
     *                       — including it would make the number sag every time somebody presses go
     * @param medianSeconds  the median, deliberately, not the mean: one ten-minute run on a local
     *                       model drags an average far enough that it stops describing anything.
     *                       Fractional, because a run that takes four tenths of a second is not a
     *                       run that takes no time at all
     * @param topActions     what the application is actually used for, which on an open-source
     *                       project is a more interesting question than what it costs
     * @param topErrors      what breaks most often, by the message the run recorded
     */
    public record Runs(long total, List<Count> byStatus, Double successRate, Double medianSeconds,
                       List<Count> topActions, List<Count> topErrors) {

        public static Runs none() {
            return new Runs(0, List.of(), null, null, List.of(), List.of());
        }
    }

    /**
     * A tally with no money in it.
     *
     * @param label what was counted: a status, an action type, an error
     * @param count how many
     */
    public record Count(String label, long count) {
    }

    /**
     * The same buckets, split by whose call it was — a slice that also says whose it was.
     * <p>
     * Flat rather than nested inside the bucket, so a screen drawing only the total never walks
     * past a dimension it is not using. Empty when every call in range belongs to the same person
     * (or to nobody), because a stack of one segment is a plain bar wearing a legend.
     *
     * @param label       the time bucket or category this belongs to
     * @param userId      the account, or null for work nobody owns — everything from before
     *                    accounts were switched on
     * @param name        their name, or "No owner", so a legend can be drawn without a second call
     * @param calls       how many calls
     * @param totalTokens prompt and answer together
     * @param cost        what they cost, where a price was known
     */
    public record UserBucket(String label, Long userId, String name, long calls, long totalTokens,
                             BigDecimal cost) {
    }
}
