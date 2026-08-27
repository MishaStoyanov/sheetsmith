package com.ap0stole.sheetsmith.domain.dto;

import com.ap0stole.sheetsmith.domain.enums.JobStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Everything the history screen can ask for. Every field is optional and null means "do not filter
 * by this", so one request shape serves the empty screen and the narrowest question alike.
 *
 * @param userIds        who started the run; combined with {@code includeUnowned} rather than
 *                       replaced by it, because a run with no owner cannot be named by an id and
 *                       would otherwise be unreachable — which is most of them on an instance that
 *                       never turned authentication on
 * @param includeUnowned include runs nobody owns
 * @param minDurationMs  runs that took at least this long
 * @param minTokens      runs that spent at least this many tokens
 * @param failedOnly     runs that failed outright, as a shortcut past the status list
 * @param sort           a field from the service's allowlist, never an arbitrary property path
 */
public record HistorySearchRequest(
        String keyword,
        LocalDateTime from,
        LocalDateTime to,
        List<Long> userIds,
        Boolean includeUnowned,
        List<JobStatus> statuses,
        List<String> providers,
        List<String> models,
        Long minDurationMs,
        Long minTokens,
        Boolean failedOnly,
        Integer page,
        Integer size,
        String sort,
        String direction) {

    /** An unfiltered first page, for a screen that has not been touched yet. */
    public static HistorySearchRequest unfiltered() {
        return new HistorySearchRequest(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }
}
