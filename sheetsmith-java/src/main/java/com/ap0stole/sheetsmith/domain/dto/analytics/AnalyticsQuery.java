package com.ap0stole.sheetsmith.domain.dto.analytics;

import com.ap0stole.sheetsmith.domain.enums.UsageKind;

import java.time.LocalDateTime;
import java.util.List;

/**
 * What to measure over.
 * <p>
 * Deliberately not the history filter's shape. That one asks about runs — status, how long they
 * took, the file they touched — and a call to a model has none of those. Reusing it would put four
 * fields on the screen that quietly do nothing.
 *
 * @param granularity how to bucket the time series: {@code day}, {@code week}, {@code month} or
 *                    {@code year}
 */
public record AnalyticsQuery(
        LocalDateTime from,
        LocalDateTime to,
        List<Long> userIds,
        Boolean includeUnowned,
        List<String> providers,
        List<String> models,
        List<UsageKind> kinds,
        String granularity) {

    public static AnalyticsQuery unfiltered() {
        return new AnalyticsQuery(null, null, null, null, null, null, null, "day");
    }
}
