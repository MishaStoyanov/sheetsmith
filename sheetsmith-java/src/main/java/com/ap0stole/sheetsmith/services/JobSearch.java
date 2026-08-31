package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.domain.dto.HistorySearchRequest;
import com.ap0stole.sheetsmith.domain.entity.JobRecord;
import com.ap0stole.sheetsmith.domain.enums.JobStatus;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.CriteriaBuilder;
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

    // The sortable columns, each written in the allowlist and again in the specification that
    // reads it: a pair that stops agreeing sorts by something else and says nothing.
    private static final String CREATED_AT = "createdAt";
    private static final String STATUS = "status";
    private static final String INPUT_FILENAME = "inputFilename";
    private static final String TOTAL_TOKENS = "totalTokens";
    private static final String MODEL = "model";
    private static final String PROVIDER = "provider";
    private static final String STARTED_BY = "startedBy";

    /**
     * What a caller may order by, mapped from the name the API uses to the property it means.
     * <p>
     * An allowlist rather than handing the string to {@link Sort}: a Sort built from user input
     * reaches any property of the entity <em>and its relations</em>, so without this the history
     * could be ordered by the owner's password hash — and the order alone leaks something.
     */
    private static final Map<String, String> SORTABLE = Map.of(
            CREATED_AT, CREATED_AT,
            STATUS, STATUS,
            INPUT_FILENAME, INPUT_FILENAME,
            TOTAL_TOKENS, TOTAL_TOKENS,
            MODEL, MODEL,
            PROVIDER, PROVIDER,
            STARTED_BY, "startedBy.name",
            "duration", "processingFinishedAt");

    private static final int MAX_PAGE_SIZE = 200;

    private JobSearch() {
    }

    /**
     * Every filter the history screen offers, as one predicate.
     * <p>
     * Split into four because they are four different questions — words, dates, whose, and what the
     * run was — and reading one of them should not mean reading the other three.
     */
    public static Specification<JobRecord> of(HistorySearchRequest request) {
        return (root, query, cb) -> {
            List<Predicate> and = new ArrayList<>();
            words(request, root, cb, and);
            dates(request, root, cb, and);
            owner(request, root, cb, and);
            aboutTheRun(request, root, cb, and);
            return cb.and(and.toArray(Predicate[]::new));
        };
    }

    /** One keyword, matched against what was asked for and against the file it was asked about. */
    private static void words(HistorySearchRequest request, Root<JobRecord> root,
                              CriteriaBuilder cb, List<Predicate> and) {
        if (has(request.keyword())) {
            String like = "%" + request.keyword().trim().toLowerCase() + "%";
            and.add(cb.or(
                    cb.like(cb.lower(root.get("instruction")), like),
                    cb.like(cb.lower(root.get(INPUT_FILENAME)), like)));
        }
    }

    private static void dates(HistorySearchRequest request, Root<JobRecord> root,
                              CriteriaBuilder cb, List<Predicate> and) {
        if (request.from() != null) {
            and.add(cb.greaterThanOrEqualTo(root.get(CREATED_AT), request.from()));
        }
        if (request.to() != null) {
            and.add(cb.lessThanOrEqualTo(root.get(CREATED_AT), request.to()));
        }
    }

    /**
     * Owner and "no owner" are one question with two halves.
     * <p>
     * Asking for both means "these people, or nobody" — and on an instance that never turned
     * authentication on, nobody is who made every run there is.
     */
    private static void owner(HistorySearchRequest request, Root<JobRecord> root,
                              CriteriaBuilder cb, List<Predicate> and) {
        boolean unowned = Boolean.TRUE.equals(request.includeUnowned());
        boolean named = request.userIds() != null && !request.userIds().isEmpty();
        if (named && unowned) {
            and.add(cb.or(root.get(STARTED_BY).get("id").in(request.userIds()),
                    cb.isNull(root.get(STARTED_BY))));
        } else if (named) {
            and.add(root.get(STARTED_BY).get("id").in(request.userIds()));
        } else if (unowned) {
            and.add(cb.isNull(root.get(STARTED_BY)));
        }
    }

    /** How it went, what ran it, and how much it took. */
    private static void aboutTheRun(HistorySearchRequest request, Root<JobRecord> root,
                                    CriteriaBuilder cb, List<Predicate> and) {
        if (notEmpty(request.statuses())) {
            and.add(root.get(STATUS).in(request.statuses()));
        }
        if (Boolean.TRUE.equals(request.failedOnly())) {
            and.add(cb.equal(root.get(STATUS), JobStatus.FAILED));
        }
        if (notEmpty(request.providers())) {
            and.add(root.get(PROVIDER).in(request.providers()));
        }
        if (notEmpty(request.models())) {
            and.add(root.get(MODEL).in(request.models()));
        }
        if (request.minTokens() != null) {
            and.add(cb.greaterThanOrEqualTo(root.get(TOTAL_TOKENS), request.minTokens()));
        }
        if (request.minDurationMs() != null) {
            and.add(tookAtLeast(request.minDurationMs(), root, cb));
        }
    }

    /**
     * Ran for at least this long.
     * <p>
     * A run still going has no finish time, so it is neither long enough nor short enough —
     * excluding it is the honest answer to "took at least this long".
     */
    private static Predicate tookAtLeast(long minDurationMs, Root<JobRecord> root, CriteriaBuilder cb) {
        var started = root.<java.time.LocalDateTime>get("processingStartedAt");
        var finished = root.<java.time.LocalDateTime>get("processingFinishedAt");
        return cb.and(
                cb.isNotNull(started),
                cb.isNotNull(finished),
                cb.greaterThanOrEqualTo(
                        cb.function("date_part", Double.class, cb.literal("epoch"),
                                cb.function("age", java.time.LocalDateTime.class, finished, started)),
                        minDurationMs / 1000.0));
    }

    public static Pageable pageable(HistorySearchRequest request) {
        int page = request.page() == null ? 0 : Math.max(0, request.page());
        int size = request.size() == null ? 20 : Math.clamp(request.size(), 1, MAX_PAGE_SIZE);

        String asked = request.sort() == null ? CREATED_AT : request.sort();
        String property = SORTABLE.get(asked);
        if (property == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Cannot sort runs by '" + asked + "'; try one of " + SORTABLE.keySet(), "sort");
        }

        // Newest first is the default because a history is read from the end.
        Sort.Direction direction = direction(request.direction(), asked);

        return PageRequest.of(page, size, Sort.by(direction, property));
    }

    private static boolean has(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean notEmpty(List<?> values) {
        return values != null && !values.isEmpty();
    }

    /**
     * Which way to sort, and the default that makes a history readable.
     * <p>
     * Newest first when sorting by date and nothing was asked for, because a history is read from
     * the end; ascending for every other column, because a name or a status is read from the start.
     */
    private static Sort.Direction direction(String asked, String column) {
        if (asked != null) {
            return "desc".equalsIgnoreCase(asked) ? Sort.Direction.DESC : Sort.Direction.ASC;
        }
        return CREATED_AT.equals(column) ? Sort.Direction.DESC : Sort.Direction.ASC;
    }
}
