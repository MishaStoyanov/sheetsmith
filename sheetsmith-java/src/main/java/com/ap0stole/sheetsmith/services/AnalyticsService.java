package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.domain.dto.analytics.AnalyticsQuery;
import com.ap0stole.sheetsmith.domain.dto.analytics.AnalyticsSummaryDto;
import com.ap0stole.sheetsmith.domain.entity.ModelPrice;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import com.ap0stole.sheetsmith.repository.ModelPriceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * The numbers behind the analytics screen.
 * <p>
 * Aggregation happens in the database — {@code group by}, not a loop over rows — because the only
 * thing coming back is a handful of numbers, and reading a table into memory to measure it gets
 * slower exactly as the instance gets more interesting.
 * <p>
 * Cost is worked out in Java afterwards rather than joined in SQL. The price table is small enough
 * to hold, and putting the arithmetic in one place is what makes "some of this could not be priced"
 * expressible at all: an SQL join would silently drop the unpriced rows or silently count them as
 * zero, and both are a wrong total presented as a right one.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    /** Postgres truncation units, allow-listed so the granularity cannot arrive as SQL. */
    private static final Map<String, String> BUCKETS = Map.of(
            "day", "day", "week", "week", "month", "month", "year", "year");

    private static final BigDecimal MILLION = new BigDecimal("1000000");

    private final JdbcTemplate jdbc;
    private final ModelPriceRepository prices;

    @Transactional(readOnly = true)
    public AnalyticsSummaryDto summary(AnalyticsQuery query) {
        Where where = Where.from(query);
        Map<String, ModelPrice> priceList = priceList();

        List<Row> rows = jdbc.query("""
                        select coalesce(provider, 'unknown') as provider,
                               coalesce(model, 'unknown')    as model,
                               user_id,
                               count(*)                      as calls,
                               coalesce(sum(prompt_tokens), 0)     as prompt_tokens,
                               coalesce(sum(completion_tokens), 0) as completion_tokens,
                               coalesce(sum(total_tokens), 0)      as total_tokens
                        from llm_usage
                        """ + where.sql() + """
                         group by provider, model, user_id
                        """,
                (rs, i) -> new Row(rs.getString("provider"), rs.getString("model"),
                        rs.getObject("user_id") == null ? null : rs.getLong("user_id"),
                        rs.getLong("calls"), rs.getLong("prompt_tokens"),
                        rs.getLong("completion_tokens"), rs.getLong("total_tokens")),
                where.args());

        Set<String> unpriced = new TreeSet<>();
        for (Row row : rows) {
            if (!priceList.containsKey(key(row.provider(), row.model()))) {
                unpriced.add(row.provider() + " / " + row.model());
            }
        }
        boolean costKnown = !priceList.isEmpty() && unpriced.size() < rows.size();

        // Read once, aggregated twice. The total per bucket and the same buckets split by owner
        // are the same rows summed along different axes; two queries could disagree with each
        // other, which is the whole reason this endpoint is one call and not five.
        List<Row> timeRows = overTimeRows(query, where);
        Map<Long, String> names = names();

        return new AnalyticsSummaryDto(
                totals(rows, priceList, where),
                slices(rows, priceList, Row::provider),
                slices(rows, priceList, r -> r.provider() + " / " + r.model()),
                byUser(rows, priceList, names, documentsPerUser(where)),
                overTime(timeRows, priceList),
                overTimeByUser(timeRows, priceList, names),
                runs(query),
                costKnown,
                List.copyOf(unpriced));
    }

    // ── The pieces ────────────────────────────────────────────────────────────

    private AnalyticsSummaryDto.Totals totals(List<Row> rows, Map<String, ModelPrice> priceList, Where where) {
        long calls = rows.stream().mapToLong(Row::calls).sum();
        long prompt = rows.stream().mapToLong(Row::promptTokens).sum();
        long completion = rows.stream().mapToLong(Row::completionTokens).sum();
        long total = rows.stream().mapToLong(Row::totalTokens).sum();

        // Documents worked on, counted as sessions rather than as distinct filenames: two different
        // reports both called "report.xlsx" are two documents, and the same file opened twice is
        // two pieces of work. Whichever is chosen, the screen has to say which — this one is
        // countable and means something.
        Long documents = jdbc.queryForObject(
                "select count(distinct session_id) from llm_usage " + where.sql(), Long.class, where.args());
        Long runs = jdbc.queryForObject(
                "select count(distinct job_id) from llm_usage " + where.sql(), Long.class, where.args());

        return new AnalyticsSummaryDto.Totals(calls, prompt, completion, total,
                cost(rows, priceList), documents == null ? 0 : documents, runs == null ? 0 : runs);
    }

    private List<AnalyticsSummaryDto.Slice> slices(List<Row> rows, Map<String, ModelPrice> priceList,
                                                   java.util.function.Function<Row, String> label) {
        Map<String, List<Row>> grouped = new LinkedHashMap<>();
        for (Row row : rows) {
            grouped.computeIfAbsent(label.apply(row), k -> new ArrayList<>()).add(row);
        }
        return grouped.entrySet().stream()
                .map(e -> new AnalyticsSummaryDto.Slice(e.getKey(),
                        e.getValue().stream().mapToLong(Row::calls).sum(),
                        e.getValue().stream().mapToLong(Row::totalTokens).sum(),
                        cost(e.getValue(), priceList)))
                .sorted(Comparator.comparingLong(AnalyticsSummaryDto.Slice::totalTokens).reversed())
                .toList();
    }

    private List<AnalyticsSummaryDto.UserSlice> byUser(List<Row> rows, Map<String, ModelPrice> priceList,
                                                       Map<Long, String> names,
                                                       Map<Long, Long> documents) {
        Map<Long, List<Row>> grouped = new LinkedHashMap<>();
        for (Row row : rows) {
            grouped.computeIfAbsent(row.userId(), k -> new ArrayList<>()).add(row);
        }

        return grouped.entrySet().stream()
                .map(e -> new AnalyticsSummaryDto.UserSlice(e.getKey(),
                        name(e.getKey(), names),
                        e.getValue().stream().mapToLong(Row::calls).sum(),
                        e.getValue().stream().mapToLong(Row::totalTokens).sum(),
                        cost(e.getValue(), priceList),
                        documents.getOrDefault(e.getKey(), 0L)))
                .sorted(Comparator.comparingLong(AnalyticsSummaryDto.UserSlice::totalTokens).reversed())
                .toList();
    }

    /** The time rows as the database returned them, still carrying the owner. */
    private List<Row> overTimeRows(AnalyticsQuery query, Where where) {
        String unit = BUCKETS.get(query.granularity() == null ? "day" : query.granularity().toLowerCase());
        if (unit == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Cannot group by '" + query.granularity() + "'; try one of " + new TreeSet<>(BUCKETS.keySet()),
                    "granularity");
        }

        return jdbc.query("""
                        select to_char(date_trunc('%s', started_at), 'YYYY-MM-DD') as bucket,
                               coalesce(provider, 'unknown') as provider,
                               coalesce(model, 'unknown')    as model,
                               user_id,
                               count(*) as calls,
                               coalesce(sum(prompt_tokens), 0)     as prompt_tokens,
                               coalesce(sum(completion_tokens), 0) as completion_tokens,
                               coalesce(sum(total_tokens), 0)      as total_tokens
                        from llm_usage
                        """.formatted(unit) + where.sql() + """
                         group by bucket, provider, model, user_id order by bucket
                        """,
                (rs, i) -> new Row(rs.getString("provider"), rs.getString("model"),
                        rs.getObject("user_id") == null ? null : rs.getLong("user_id"),
                        rs.getLong("calls"), rs.getLong("prompt_tokens"),
                        rs.getLong("completion_tokens"), rs.getLong("total_tokens"))
                        .withBucket(rs.getString("bucket")),
                where.args());
    }

    /**
     * Documents per person, counted in the database rather than summed from the grouped rows.
     * <p>
     * Distinct is not additive: the same document worked on with two models is two rows and one
     * document, so adding up per-model counts would report it twice. This is also why the numbers
     * here do not add up to the instance total, and correctly so — two people on one document are
     * two rows here and one document there.
     */
    private Map<Long, Long> documentsPerUser(Where where) {
        Map<Long, Long> counts = new HashMap<>();
        jdbc.query("select user_id, count(distinct session_id) as documents from llm_usage "
                        + where.sql() + " group by user_id",
                rs -> {
                    Long userId = rs.getObject("user_id") == null ? null : rs.getLong("user_id");
                    counts.put(userId, rs.getLong("documents"));
                },
                where.args());
        return counts;
    }

    private List<AnalyticsSummaryDto.Bucket> overTime(List<Row> rows, Map<String, ModelPrice> priceList) {
        Map<String, List<Row>> grouped = new LinkedHashMap<>();
        for (Row row : rows) {
            grouped.computeIfAbsent(row.bucket(), k -> new ArrayList<>()).add(row);
        }
        return grouped.entrySet().stream()
                .map(e -> new AnalyticsSummaryDto.Bucket(e.getKey(),
                        e.getValue().stream().mapToLong(Row::calls).sum(),
                        e.getValue().stream().mapToLong(Row::totalTokens).sum(),
                        cost(e.getValue(), priceList)))
                .toList();
    }

    /**
     * The buckets split by owner, or nothing at all.
     * <p>
     * One owner in range means one segment per bar, which is the plain chart again with a legend
     * bolted on — so it answers empty and the screen draws the total it already has. The rule lives
     * here rather than on the screen because the question is about the data, and the data is here.
     */
    private List<AnalyticsSummaryDto.UserBucket> overTimeByUser(List<Row> rows,
                                                                Map<String, ModelPrice> priceList,
                                                                Map<Long, String> names) {
        long owners = rows.stream().map(Row::userId).map(String::valueOf).distinct().count();
        if (owners < 2) {
            return List.of();
        }

        Map<String, List<Row>> grouped = new LinkedHashMap<>();
        for (Row row : rows) {
            grouped.computeIfAbsent(row.bucket() + "\u0000" + row.userId(), k -> new ArrayList<>()).add(row);
        }
        return grouped.values().stream()
                .map(group -> new AnalyticsSummaryDto.UserBucket(
                        group.getFirst().bucket(),
                        group.getFirst().userId(),
                        name(group.getFirst().userId(), names),
                        group.stream().mapToLong(Row::calls).sum(),
                        group.stream().mapToLong(Row::totalTokens).sum(),
                        cost(group, priceList)))
                .toList();
    }

    /**
     * Named as what it is. Calls made before there were accounts, or on an instance with none,
     * belong to nobody — and on most instances that is all of them.
     */
    private static String name(Long userId, Map<Long, String> names) {
        if (userId == null) {
            return "No owner";
        }
        return names.getOrDefault(userId, "Deleted account");
    }

    private Map<Long, String> names() {
        Map<Long, String> names = new HashMap<>();
        jdbc.query("select id, name from users", rs -> { names.put(rs.getLong("id"), rs.getString("name")); });
        return names;
    }

    // ── How the runs went ─────────────────────────────────────────────────────

    /**
     * The other half of the screen: not what was asked of a model, but whether it worked.
     * <p>
     * These come from the run tables rather than from the call log, and the two do not have the
     * same shape — one run makes several calls, and a run that never reached a model makes none.
     * They are in the same answer anyway because they are drawn under the same filters, and a
     * second request could come back describing a different slice of time.
     */
    private AnalyticsSummaryDto.Runs runs(AnalyticsQuery query) {
        Where where = Where.forRuns(query, "");

        List<AnalyticsSummaryDto.Count> byStatus = jdbc.query(
                "select status, count(*) as runs from job_records " + where.sql()
                        + " group by status order by runs desc",
                (rs, i) -> new AnalyticsSummaryDto.Count(rs.getString("status"), rs.getLong("runs")),
                where.args());

        long total = byStatus.stream().mapToLong(AnalyticsSummaryDto.Count::count).sum();
        if (total == 0) {
            return AnalyticsSummaryDto.Runs.none();
        }

        // A run still in flight has not failed, and counting it as anything but pending would make
        // the rate sag every time somebody pressed go.
        long decided = byStatus.stream()
                .filter(status -> !"PROCESSING".equals(status.label()))
                .mapToLong(AnalyticsSummaryDto.Count::count).sum();
        long completed = byStatus.stream()
                .filter(status -> "COMPLETED".equals(status.label()))
                .mapToLong(AnalyticsSummaryDto.Count::count).sum();
        Double successRate = decided == 0 ? null : (double) completed / decided;

        // The median in the database, where it is one function rather than a sort and an index
        // calculation with an off-by-one waiting in it for the even case.
        Double median = jdbc.queryForObject("""
                select percentile_cont(0.5) within group (
                           order by extract(epoch from (processing_finished_at - processing_started_at)))
                from job_records
                """ + and(where, "processing_started_at is not null and processing_finished_at is not null"),
                Double.class, where.args());

        Where joined = Where.forRuns(query, "j.");
        List<AnalyticsSummaryDto.Count> topActions = jdbc.query("""
                select a.action_type as label, count(*) as runs
                from action_results a join job_records j on j.id = a.job_id
                """ + joined.sql() + " group by label order by runs desc, label limit 8",
                (rs, i) -> new AnalyticsSummaryDto.Count(rs.getString("label"), rs.getLong("runs")),
                joined.args());

        List<AnalyticsSummaryDto.Count> topErrors = jdbc.query("""
                select coalesce(nullif(a.error_message, ''), '(no message recorded)') as label,
                       count(*) as runs
                from action_results a join job_records j on j.id = a.job_id
                """ + and(joined, "a.success = false") + " group by label order by runs desc, label limit 5",
                (rs, i) -> new AnalyticsSummaryDto.Count(rs.getString("label"), rs.getLong("runs")),
                joined.args());

        return new AnalyticsSummaryDto.Runs(total, byStatus, successRate, median, topActions, topErrors);
    }

    /** One more condition on a clause that may or may not already have a {@code where}. */
    private static String and(Where where, String condition) {
        return where.sql().isEmpty() ? " where " + condition : where.sql() + " and " + condition;
    }

    // ── Money ─────────────────────────────────────────────────────────────────

    /**
     * What these calls cost, or null if none of them could be priced.
     * <p>
     * Rows with no price contribute nothing rather than zero, and a group where nothing is priced
     * answers null rather than 0.00 — a total that silently leaves out half the calls is worse than
     * an absent one, because it looks like an answer.
     */
    private BigDecimal cost(List<Row> rows, Map<String, ModelPrice> priceList) {
        BigDecimal sum = BigDecimal.ZERO;
        boolean anyPriced = false;

        for (Row row : rows) {
            ModelPrice price = priceList.get(key(row.provider(), row.model()));
            if (price == null) {
                continue;
            }
            anyPriced = true;
            sum = sum.add(price.getInputPerMillion()
                            .multiply(BigDecimal.valueOf(row.promptTokens())).divide(MILLION, 6, RoundingMode.HALF_UP))
                    .add(price.getOutputPerMillion()
                            .multiply(BigDecimal.valueOf(row.completionTokens())).divide(MILLION, 6, RoundingMode.HALF_UP));
        }
        return anyPriced ? sum.setScale(4, RoundingMode.HALF_UP) : null;
    }

    private Map<String, ModelPrice> priceList() {
        Map<String, ModelPrice> map = new HashMap<>();
        prices.findAll().forEach(price -> map.put(key(price.getProvider(), price.getModel()), price));
        return map;
    }

    private static String key(String provider, String model) {
        return (provider == null ? "" : provider.toUpperCase()) + " " + (model == null ? "" : model);
    }

    // ── Filters ───────────────────────────────────────────────────────────────

    /** The where clause and its arguments, built together so they cannot fall out of step. */
    private record Where(String sql, Object[] args) {

        /** The model calls: when a call started, and every filter the screen offers. */
        static Where from(AnalyticsQuery query) {
            return build(query, "", "started_at", true);
        }

        /**
         * The runs, which are a different table asked a different question.
         * <p>
         * Time is when the run was asked for rather than when a call inside it started, and the
         * kind filter is left out entirely: chat and improve describe a call to a model, and a run
         * has no such thing. A filter that silently matched nothing would be worse than an absent
         * one.
         */
        static Where forRuns(AnalyticsQuery query, String prefix) {
            return build(query, prefix, "created_at", false);
        }

        private static Where build(AnalyticsQuery query, String prefix, String timeColumn, boolean withKinds) {
            List<String> clauses = new ArrayList<>();
            List<Object> args = new ArrayList<>();
            java.util.function.UnaryOperator<String> column = name -> prefix + name;

            if (query.from() != null) {
                clauses.add(column.apply(timeColumn) + " >= ?");
                args.add(java.sql.Timestamp.valueOf(query.from()));
            }
            if (query.to() != null) {
                clauses.add(column.apply(timeColumn) + " <= ?");
                args.add(java.sql.Timestamp.valueOf(query.to()));
            }

            // Owner and "no owner" are one question with two halves, exactly as in the history:
            // every call on an instance without accounts belongs to nobody.
            boolean named = query.userIds() != null && !query.userIds().isEmpty();
            boolean unowned = Boolean.TRUE.equals(query.includeUnowned());
            String userId = column.apply("user_id");
            if (named && unowned) {
                clauses.add("(" + userId + " in (" + placeholders(query.userIds().size()) + ") or " + userId + " is null)");
                args.addAll(query.userIds());
            } else if (named) {
                clauses.add(userId + " in (" + placeholders(query.userIds().size()) + ")");
                args.addAll(query.userIds());
            } else if (unowned) {
                clauses.add(userId + " is null");
            }

            if (notEmpty(query.providers())) {
                clauses.add(column.apply("provider") + " in (" + placeholders(query.providers().size()) + ")");
                args.addAll(query.providers());
            }
            if (notEmpty(query.models())) {
                clauses.add(column.apply("model") + " in (" + placeholders(query.models().size()) + ")");
                args.addAll(query.models());
            }
            if (withKinds && notEmpty(query.kinds())) {
                clauses.add(column.apply("kind") + " in (" + placeholders(query.kinds().size()) + ")");
                query.kinds().forEach(kind -> args.add(kind.name()));
            }

            return new Where(clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses), args.toArray());
        }

        private static String placeholders(int count) {
            return String.join(", ", Collections.nCopies(count, "?"));
        }

        private static boolean notEmpty(List<?> values) {
            return values != null && !values.isEmpty();
        }
    }

    /** One grouped row as the database returned it. */
    private record Row(String provider, String model, Long userId, long calls,
                       long promptTokens, long completionTokens, long totalTokens, String bucket) {

        Row(String provider, String model, Long userId, long calls,
            long promptTokens, long completionTokens, long totalTokens) {
            this(provider, model, userId, calls, promptTokens, completionTokens, totalTokens, null);
        }

        Row withBucket(String bucket) {
            return new Row(provider, model, userId, calls, promptTokens, completionTokens, totalTokens, bucket);
        }
    }
}
