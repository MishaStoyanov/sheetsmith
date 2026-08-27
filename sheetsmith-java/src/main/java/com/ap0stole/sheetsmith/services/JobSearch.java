package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.domain.dto.HistorySearchRequest;
import com.ap0stole.sheetsmith.domain.entity.JobRecord;
import com.ap0stole.sheetsmith.domain.enums.JobStatus;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a filter request into a query.
 * <p>
 * Built as a {@link Specification} rather than by assembling SQL: the filters combine freely, and
 * string concatenation with a dozen optional clauses is where injection bugs and stray {@code AND}s
 * come from in equal measure.
 */
public final class JobSearch {

    /**
     * What a caller may order by, mapped from the name the API uses to the property it means.
     * <p>
     * An allowlist rather than handing the string to {@link Sort}: a Sort built from user input
     * reaches any property of the entity <em>and its relations</em>, so without this the history
     * could be ordered by the owner's password hash — and the order alone leaks something.
     */
    private static final Map<String, String> SORTABLE = Map.of(
            "createdAt", "createdAt",
            "status", "status",
            "inputFilename", "inputFilename",
            "totalTokens", "totalTokens",
            "model", "model",
            "provider", "provider",
            "startedBy", "startedBy.name",
            "duration", "processingFinishedAt");

    private static final int MAX_PAGE_SIZE = 200;

    private JobSearch() {
    }

    public static Specification<JobRecord> of(HistorySearchRequest request) {
        return (root, query, cb) -> {
            List<Predicate> and = new ArrayList<>();

            if (has(request.keyword())) {
                String like = "%" + request.keyword().trim().toLowerCase() + "%";
                and.add(cb.or(
                        cb.like(cb.lower(root.get("instruction")), like),
                        cb.like(cb.lower(root.get("inputFilename")), like)));
            }
            if (request.from() != null) {
                and.add(cb.greaterThanOrEqualTo(root.get("createdAt"), request.from()));
            }
            if (request.to() != null) {
                and.add(cb.lessThanOrEqualTo(root.get("createdAt"), request.to()));
            }

            // Owner and "no owner" are one question with two halves. Asking for both means "these
            // people, or nobody" — and on an instance that never turned authentication on, nobody
            // is who made every run there is.
            boolean unowned = Boolean.TRUE.equals(request.includeUnowned());
            boolean named = request.userIds() != null && !request.userIds().isEmpty();
            if (named && unowned) {
                and.add(cb.or(root.get("startedBy").get("id").in(request.userIds()),
                        cb.isNull(root.get("startedBy"))));
            } else if (named) {
                and.add(root.get("startedBy").get("id").in(request.userIds()));
            } else if (unowned) {
                and.add(cb.isNull(root.get("startedBy")));
            }

            if (notEmpty(request.statuses())) {
                and.add(root.get("status").in(request.statuses()));
            }
            if (Boolean.TRUE.equals(request.failedOnly())) {
                and.add(cb.equal(root.get("status"), JobStatus.FAILED));
            }
            if (notEmpty(request.providers())) {
                and.add(root.get("provider").in(request.providers()));
            }
            if (notEmpty(request.models())) {
                and.add(root.get("model").in(request.models()));
            }
            if (request.minTokens() != null) {
                and.add(cb.greaterThanOrEqualTo(root.get("totalTokens"), request.minTokens()));
            }
            if (request.minDurationMs() != null) {
                // A run still going has no finish time, so it is neither long enough nor short
                // enough — excluding it is the honest answer to "took at least this long".
                var started = root.<java.time.LocalDateTime>get("processingStartedAt");
                var finished = root.<java.time.LocalDateTime>get("processingFinishedAt");
                and.add(cb.and(
                        cb.isNotNull(started),
                        cb.isNotNull(finished),
                        cb.greaterThanOrEqualTo(
                                cb.function("date_part", Double.class, cb.literal("epoch"),
                                        cb.function("age", java.time.LocalDateTime.class, finished, started)),
                                request.minDurationMs() / 1000.0)));
            }

            return cb.and(and.toArray(Predicate[]::new));
        };
    }

    public static Pageable pageable(HistorySearchRequest request) {
        int page = request.page() == null ? 0 : Math.max(0, request.page());
        int size = request.size() == null ? 20 : Math.clamp(request.size(), 1, MAX_PAGE_SIZE);

        String asked = request.sort() == null ? "createdAt" : request.sort();
        String property = SORTABLE.get(asked);
        if (property == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Cannot sort runs by '" + asked + "'; try one of " + SORTABLE.keySet(), "sort");
        }

        // Newest first is the default because a history is read from the end.
        Sort.Direction direction = request.direction() == null
                ? ("createdAt".equals(asked) ? Sort.Direction.DESC : Sort.Direction.ASC)
                : ("desc".equalsIgnoreCase(request.direction()) ? Sort.Direction.DESC : Sort.Direction.ASC);

        return PageRequest.of(page, size, Sort.by(direction, property));
    }

    private static boolean has(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean notEmpty(List<?> values) {
        return values != null && !values.isEmpty();
    }
}
