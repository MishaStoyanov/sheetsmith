package com.ap0stole.sheetsmith.schema;

import com.ap0stole.sheetsmith.domain.dto.analytics.AnalyticsQuery;
import com.ap0stole.sheetsmith.domain.dto.analytics.AnalyticsSummaryDto;
import com.ap0stole.sheetsmith.domain.dto.price.UpsertPriceRequest;
import com.ap0stole.sheetsmith.domain.enums.UsageKind;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.llm.LlmEngine;
import com.ap0stole.sheetsmith.llm.TokenUsage;
import com.ap0stole.sheetsmith.services.AnalyticsService;
import com.ap0stole.sheetsmith.services.ModelPriceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The numbers, and the several ways a summary can be confidently wrong.
 */
@SpringBootTest
@EnabledIf(value = "dockerAvailable", disabledReason = "Docker is not available")
class AnalyticsSummaryTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AnalyticsService analytics;

    @Autowired
    private ModelPriceService prices;

    private Long danaId;

    @BeforeEach
    void seed() {
        jdbc.update("delete from action_results");
        jdbc.update("delete from llm_usage");
        jdbc.update("delete from job_records");
        jdbc.update("delete from model_prices");
        jdbc.update("delete from users where name = 'analytics-fixture'");
        jdbc.update("insert into users (name, password_hash) values ('analytics-fixture', 'x')");
        danaId = jdbc.queryForObject("select id from users where name = 'analytics-fixture'", Long.class);
    }

    /**
     * Written straight in, so the timestamps and the owner are exactly what each case needs.
     * <p>
     * The rate is stamped from the price list as it stands at insert time, which is what the
     * application does when it records a real call. A fixture that left it null would be testing a
     * call made before anybody had priced that model — a real case, but not this one.
     */
    private void call(String when, String kind, Long userId, String session,
                      String provider, String model, long prompt, long completion) {
        jdbc.update("""
                insert into llm_usage (kind, user_id, session_id, prompt, prompt_tokens,
                        completion_tokens, total_tokens, provider_mode, provider, model,
                        input_per_million, output_per_million, started_at, finished_at)
                select ?, ?, ?, 'tidy it', ?, ?, ?, 'CLOUD', ?, ?,
                       p.input_per_million, p.output_per_million, ?::timestamp, ?::timestamp
                from (select 1) as one
                left join model_prices p on upper(p.provider) = upper(?) and p.model = ?
                """, kind, userId, session, prompt, completion, prompt + completion, provider, model,
                when, when, provider, model);
    }

    /** A run, written straight in so its status and its clock are exactly what each case needs. */
    private long run(String asked, String status, Long userId, Integer seconds) {
        jdbc.update("""
                insert into job_records (created_at, instruction, input_filename, input_file_path,
                        status, user_id, processing_started_at, processing_finished_at)
                values (?::timestamp, 'tidy it', 'book.xlsx', '/tmp/book.xlsx', ?, ?,
                        ?::timestamp, case when ?::int is null then null
                                           else (?::timestamp + make_interval(secs => ?::int)) end)
                """, asked, status, userId, seconds == null ? null : asked, seconds, asked, seconds);
        return jdbc.queryForObject("select max(id) from job_records", Long.class);
    }

    private void action(long jobId, int order, String type, boolean ok, String error) {
        jdbc.update("""
                insert into action_results (job_id, execution_order, action_type, success, error_message)
                values (?, ?, ?, ?, ?)
                """, jobId, order, type, ok, error);
    }

    @Test
    @DisplayName("a run still in flight is not counted as a failure")
    void inFlightRunsAreNotFailures() {
        // The failure this guards against is a success rate that sags every time somebody presses
        // go: a run with no verdict yet is not a verdict.
        run("2026-08-01 10:00", "COMPLETED", danaId, 12);
        run("2026-08-01 10:05", "PROCESSING", danaId, null);

        AnalyticsSummaryDto.Runs runs = analytics.summary(AnalyticsQuery.unfiltered()).runs();

        assertThat(runs.total()).isEqualTo(2);
        assertThat(runs.successRate()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("partial counts against the success rate, because it did not do what was asked")
    void partialIsNotSuccess() {
        run("2026-08-01 10:00", "COMPLETED", danaId, 10);
        run("2026-08-01 10:01", "PARTIAL", danaId, 10);
        run("2026-08-01 10:02", "FAILED", danaId, 10);
        run("2026-08-01 10:03", "COMPLETED", danaId, 10);

        AnalyticsSummaryDto.Runs runs = analytics.summary(AnalyticsQuery.unfiltered()).runs();

        assertThat(runs.successRate()).isEqualTo(0.5);
        assertThat(runs.byStatus()).extracting(AnalyticsSummaryDto.Count::label)
                .containsExactlyInAnyOrder("COMPLETED", "PARTIAL", "FAILED");
    }

    @Test
    @DisplayName("the duration is the median, so one long run does not move it")
    void oneLongRunDoesNotMoveTheMedian() {
        // The whole reason for choosing the median: a ten-minute run on a local model drags an
        // average far enough that the number stops describing anything.
        run("2026-08-01 10:00", "COMPLETED", danaId, 10);
        run("2026-08-01 10:01", "COMPLETED", danaId, 12);
        run("2026-08-01 10:02", "COMPLETED", danaId, 14);
        run("2026-08-01 10:03", "COMPLETED", danaId, 600);

        AnalyticsSummaryDto.Runs runs = analytics.summary(AnalyticsQuery.unfiltered()).runs();

        assertThat(runs.medianSeconds())
                .as("the middle of 10, 12, 14 and 600 — an average would say 159")
                .isEqualTo(13.0);
    }

    @Test
    @DisplayName("a run that never finished is left out of the median rather than counted as zero")
    void unfinishedRunsAreNotZeroSeconds() {
        run("2026-08-01 10:00", "COMPLETED", danaId, 20);
        run("2026-08-01 10:01", "PROCESSING", danaId, null);

        assertThat(analytics.summary(AnalyticsQuery.unfiltered()).runs().medianSeconds()).isEqualTo(20.0);
    }

    @Test
    @DisplayName("the top actions and the top errors are ranked, and the errors are only failures")
    void actionsAndErrorsAreRanked() {
        long first = run("2026-08-01 10:00", "PARTIAL", danaId, 10);
        action(first, 0, "SET_CELL_VALUE", true, null);
        action(first, 1, "SET_CELL_VALUE", true, null);
        action(first, 2, "CREATE_CHART", false, "No numeric column to plot");

        long second = run("2026-08-01 10:05", "FAILED", danaId, 10);
        action(second, 0, "CREATE_CHART", false, "No numeric column to plot");
        action(second, 1, "FREEZE_PANES", false, "Sheet is protected");

        AnalyticsSummaryDto.Runs runs = analytics.summary(AnalyticsQuery.unfiltered()).runs();

        assertThat(runs.topActions()).extracting(AnalyticsSummaryDto.Count::label,
                        AnalyticsSummaryDto.Count::count)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("CREATE_CHART", 2L),
                        org.assertj.core.groups.Tuple.tuple("SET_CELL_VALUE", 2L),
                        org.assertj.core.groups.Tuple.tuple("FREEZE_PANES", 1L));
        assertThat(runs.topErrors()).extracting(AnalyticsSummaryDto.Count::label,
                        AnalyticsSummaryDto.Count::count)
                .as("a successful action has no place in a list of what went wrong")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("No numeric column to plot", 2L),
                        org.assertj.core.groups.Tuple.tuple("Sheet is protected", 1L));
    }

    @Test
    @DisplayName("the run figures answer to the same filters as everything else on the screen")
    void runsFollowTheFilters() {
        long inside = run("2026-08-01 10:00", "COMPLETED", danaId, 10);
        action(inside, 0, "SORT_DATA", true, null);
        long outside = run("2026-08-20 10:00", "FAILED", danaId, 10);
        action(outside, 0, "CREATE_CHART", false, "boom");

        AnalyticsQuery firstWeek = new AnalyticsQuery(
                java.time.LocalDateTime.parse("2026-08-01T00:00"),
                java.time.LocalDateTime.parse("2026-08-07T23:59"),
                null, null, null, null, null, "day");

        AnalyticsSummaryDto.Runs runs = analytics.summary(firstWeek).runs();

        assertThat(runs.total()).isEqualTo(1);
        assertThat(runs.successRate()).isEqualTo(1.0);
        assertThat(runs.topActions()).extracting(AnalyticsSummaryDto.Count::label).containsExactly("SORT_DATA");
        assertThat(runs.topErrors()).isEmpty();
    }

    @Test
    @DisplayName("an instance that has never run anything says nothing rather than zero per cent")
    void noRunsIsNotZeroPerCent() {
        call("2026-08-01 10:00", "CHAT", danaId, "s1", "OPENAI", "gpt-4o", 100, 10);

        AnalyticsSummaryDto.Runs runs = analytics.summary(AnalyticsQuery.unfiltered()).runs();

        assertThat(runs.total()).isZero();
        assertThat(runs.successRate())
                .as("nought per cent successful would read as everything having failed")
                .isNull();
        assertThat(runs.medianSeconds()).isNull();
    }

    @Test
    @DisplayName("with no prices entered, tokens are counted and money is not claimed")
    void withoutPricesThereIsNoCost() {
        call("2026-08-01 10:00", "CHAT", danaId, "s1", "OPENAI", "gpt-4o", 1000, 200);

        AnalyticsSummaryDto summary = analytics.summary(AnalyticsQuery.unfiltered());

        assertThat(summary.totals().totalTokens()).isEqualTo(1200);
        assertThat(summary.costKnown()).isFalse();
        assertThat(summary.totals().cost()).isNull();
        assertThat(summary.unpricedModels()).containsExactly("OPENAI / gpt-4o");
    }

    @Test
    @DisplayName("cost is the two rates applied to the two halves, not one rate to the total")
    void costUsesBothRates() {
        prices.upsert(new UpsertPriceRequest("OPENAI", "gpt-4o",
                new BigDecimal("2.00"), new BigDecimal("10.00")));
        call("2026-08-01 10:00", "CHAT", danaId, "s1", "OPENAI", "gpt-4o", 1_000_000, 100_000);

        AnalyticsSummaryDto summary = analytics.summary(AnalyticsQuery.unfiltered());

        // A million in at $2 plus a hundred thousand out at $10 = 2.00 + 1.00.
        assertThat(summary.totals().cost()).isEqualByComparingTo("3.0000");
        assertThat(summary.costKnown()).isTrue();
    }

    @Test
    @DisplayName("an unpriced model contributes nothing rather than zero, and is named")
    void unpricedModelsAreNamedNotSilentlyCountedAsFree() {
        // The failure this guards against is a total that looks complete: silently treating an
        // unpriced model as free reports a smaller number with no hint that it is smaller.
        prices.upsert(new UpsertPriceRequest("OPENAI", "gpt-4o",
                new BigDecimal("2.00"), new BigDecimal("10.00")));
        call("2026-08-01 10:00", "CHAT", danaId, "s1", "OPENAI", "gpt-4o", 1_000_000, 0);
        call("2026-08-01 11:00", "CHAT", danaId, "s1", "OLLAMA", "gemma4:12b", 5_000_000, 0);

        AnalyticsSummaryDto summary = analytics.summary(AnalyticsQuery.unfiltered());

        assertThat(summary.totals().cost()).isEqualByComparingTo("2.0000");
        assertThat(summary.unpricedModels()).containsExactly("OLLAMA / gemma4:12b");
        assertThat(summary.totals().totalTokens())
                .as("the tokens are all counted even where the money cannot be")
                .isEqualTo(6_000_000);
    }

    @Test
    @DisplayName("both flows are in the same totals")
    void chatAndImproveAreOneNumber() {
        call("2026-08-01 10:00", "CHAT", danaId, "s1", "OPENAI", "gpt-4o", 100, 10);
        call("2026-08-01 10:05", "IMPROVE", danaId, "s1", "OPENAI", "gpt-4o", 900, 90);

        AnalyticsSummaryDto summary = analytics.summary(AnalyticsQuery.unfiltered());

        assertThat(summary.totals().calls()).isEqualTo(2);
        assertThat(summary.totals().totalTokens()).isEqualTo(1100);
    }

    @Test
    @DisplayName("calls nobody owns are a named slice, not a gap")
    void unownedCallsAreShownAsSuch() {
        call("2026-08-01 10:00", "CHAT", null, "s1", "OLLAMA", "gemma4:12b", 100, 10);
        call("2026-08-01 10:00", "CHAT", danaId, "s2", "OLLAMA", "gemma4:12b", 200, 20);

        List<AnalyticsSummaryDto.UserSlice> byUser = analytics.summary(AnalyticsQuery.unfiltered()).byUser();

        assertThat(byUser).extracting(AnalyticsSummaryDto.UserSlice::name)
                .containsExactlyInAnyOrder("No owner", "analytics-fixture");
    }

    @Test
    @DisplayName("the time series buckets by the unit asked for")
    void timeSeriesBuckets() {
        call("2026-08-01 10:00", "CHAT", danaId, "s1", "OLLAMA", "gemma4:12b", 100, 0);
        call("2026-08-02 10:00", "CHAT", danaId, "s1", "OLLAMA", "gemma4:12b", 100, 0);
        call("2026-09-05 10:00", "CHAT", danaId, "s1", "OLLAMA", "gemma4:12b", 100, 0);

        assertThat(analytics.summary(new AnalyticsQuery(null, null, null, null, null, null, null, "day"))
                .overTime()).hasSize(3);
        assertThat(analytics.summary(new AnalyticsQuery(null, null, null, null, null, null, null, "month"))
                .overTime()).hasSize(2);
    }

    @Test
    @DisplayName("the granularity is an allowlist, not something handed to SQL")
    void granularityCannotBeAnything() {
        assertThatThrownBy(() -> analytics.summary(
                new AnalyticsQuery(null, null, null, null, null, null, null, "day'); drop table llm_usage; --")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Cannot group by");

        assertThat(jdbc.queryForObject("select count(*) from llm_usage", Integer.class)).isZero();
    }

    @Test
    @DisplayName("filters narrow every part of the answer together")
    void filtersApplyThroughout() {
        call("2026-08-01 10:00", "CHAT", danaId, "s1", "OPENAI", "gpt-4o", 100, 10);
        call("2026-08-01 10:00", "CHAT", danaId, "s2", "OLLAMA", "gemma4:12b", 900, 90);

        AnalyticsSummaryDto only = analytics.summary(new AnalyticsQuery(
                null, null, null, null, List.of("OLLAMA"), null, List.of(UsageKind.CHAT), "day"));

        assertThat(only.totals().calls()).isEqualTo(1);
        assertThat(only.byProvider()).singleElement()
                .extracting(AnalyticsSummaryDto.Slice::label).isEqualTo("OLLAMA");
        assertThat(only.overTime()).singleElement()
                .extracting(AnalyticsSummaryDto.Bucket::totalTokens).isEqualTo(990L);
    }

    @Test
    @DisplayName("documents counts the sheets worked on, not the calls made about them")
    void documentsAreCountedOncePerSession() {
        call("2026-08-01 10:00", "CHAT", danaId, "s1", "OLLAMA", "gemma4:12b", 10, 1);
        call("2026-08-01 10:01", "CHAT", danaId, "s1", "OLLAMA", "gemma4:12b", 10, 1);
        call("2026-08-01 10:02", "CHAT", danaId, "s2", "OLLAMA", "gemma4:12b", 10, 1);

        assertThat(analytics.summary(AnalyticsQuery.unfiltered()).totals().documents()).isEqualTo(2);
    }

    @Test
    @DisplayName("the split over time is left empty when every call has the same owner")
    void oneOwnerIsNotAStack() {
        call("2026-08-01 10:00", "CHAT", danaId, "s1", "OPENAI", "gpt-4o", 100, 10);
        call("2026-08-02 10:00", "CHAT", danaId, "s1", "OPENAI", "gpt-4o", 100, 10);

        AnalyticsSummaryDto summary = analytics.summary(AnalyticsQuery.unfiltered());

        assertThat(summary.overTime()).hasSize(2);
        assertThat(summary.overTimeByUser())
                .as("a stack of one segment is the plain chart wearing a legend")
                .isEmpty();
    }

    @Test
    @DisplayName("the split over time names the owner of every part, unowned calls included")
    void twoOwnersSplitEachBucket() {
        call("2026-08-01 10:00", "CHAT", danaId, "s1", "OPENAI", "gpt-4o", 100, 10);
        call("2026-08-01 11:00", "CHAT", null, "s1", "OPENAI", "gpt-4o", 500, 50);
        call("2026-08-02 10:00", "CHAT", danaId, "s1", "OPENAI", "gpt-4o", 700, 70);

        List<AnalyticsSummaryDto.UserBucket> split = analytics.summary(AnalyticsQuery.unfiltered()).overTimeByUser();

        assertThat(split).extracting(AnalyticsSummaryDto.UserBucket::label,
                        AnalyticsSummaryDto.UserBucket::name, AnalyticsSummaryDto.UserBucket::totalTokens)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("2026-08-01", "analytics-fixture", 110L),
                        org.assertj.core.groups.Tuple.tuple("2026-08-01", "No owner", 550L),
                        org.assertj.core.groups.Tuple.tuple("2026-08-02", "analytics-fixture", 770L));
    }

    @Test
    @DisplayName("the parts of a bucket add up to the bucket")
    void thePartsAddUpToTheWhole() {
        // The chart draws the bar from its own parts, so a split that does not sum to the total is
        // a stack that overshoots or falls short of the axis it is drawn against.
        prices.upsert(new UpsertPriceRequest("OPENAI", "gpt-4o",
                new BigDecimal("2.00"), new BigDecimal("10.00")));
        call("2026-08-01 10:00", "CHAT", danaId, "s1", "OPENAI", "gpt-4o", 1_000_000, 100_000);
        call("2026-08-01 11:00", "CHAT", null, "s1", "OPENAI", "gpt-4o", 3_000_000, 200_000);

        AnalyticsSummaryDto summary = analytics.summary(AnalyticsQuery.unfiltered());

        AnalyticsSummaryDto.Bucket whole = summary.overTime().getFirst();
        List<AnalyticsSummaryDto.UserBucket> parts = summary.overTimeByUser();

        assertThat(parts.stream().mapToLong(AnalyticsSummaryDto.UserBucket::totalTokens).sum())
                .isEqualTo(whole.totalTokens());
        assertThat(parts.stream().map(AnalyticsSummaryDto.UserBucket::cost)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(whole.cost());
    }

    @Test
    @DisplayName("a filter that leaves one owner drops the split with it")
    void filteringDownToOneOwnerDropsTheSplit() {
        call("2026-08-01 10:00", "CHAT", danaId, "s1", "OPENAI", "gpt-4o", 100, 10);
        call("2026-08-01 11:00", "CHAT", null, "s1", "OPENAI", "gpt-4o", 500, 50);

        AnalyticsQuery mineOnly = new AnalyticsQuery(null, null, List.of(danaId), false, null, null, null, "day");

        assertThat(analytics.summary(AnalyticsQuery.unfiltered()).overTimeByUser()).hasSize(2);
        assertThat(analytics.summary(mineOnly).overTimeByUser()).isEmpty();
    }

    @Test
    @DisplayName("a person's documents are counted, not summed from their rows")
    void documentsPerPersonAreDistinctSessions() {
        // Two models on one document is two rows and one document. Adding up per-model counts
        // would report the same spreadsheet twice.
        call("2026-08-01 10:00", "CHAT", danaId, "s1", "OPENAI", "gpt-4o", 100, 10);
        call("2026-08-01 10:05", "CHAT", danaId, "s1", "OLLAMA", "gemma4:12b", 100, 10);
        call("2026-08-01 10:10", "CHAT", danaId, "s2", "OPENAI", "gpt-4o", 100, 10);

        AnalyticsSummaryDto.UserSlice dana = analytics.summary(AnalyticsQuery.unfiltered()).byUser().getFirst();

        assertThat(dana.calls()).isEqualTo(3);
        assertThat(dana.documents()).isEqualTo(2);
    }

    @Test
    @DisplayName("two people on one document are one document for the instance and one each")
    void documentsDoNotHaveToAddUp() {
        call("2026-08-01 10:00", "CHAT", danaId, "shared", "OPENAI", "gpt-4o", 100, 10);
        call("2026-08-01 10:05", "CHAT", null, "shared", "OPENAI", "gpt-4o", 100, 10);

        AnalyticsSummaryDto summary = analytics.summary(AnalyticsQuery.unfiltered());

        assertThat(summary.totals().documents())
                .as("the instance worked on one document")
                .isEqualTo(1);
        assertThat(summary.byUser()).extracting(AnalyticsSummaryDto.UserSlice::documents)
                .as("and each of them worked on it")
                .containsExactly(1L, 1L);
    }

    @Test
    @DisplayName("a date filter narrows a person's document count with everything else")
    void documentsFollowTheFilter() {
        call("2026-08-01 10:00", "CHAT", danaId, "s1", "OPENAI", "gpt-4o", 100, 10);
        call("2026-08-09 10:00", "CHAT", danaId, "s2", "OPENAI", "gpt-4o", 100, 10);

        AnalyticsQuery firstWeek = new AnalyticsQuery(
                java.time.LocalDateTime.parse("2026-08-01T00:00"),
                java.time.LocalDateTime.parse("2026-08-07T23:59"),
                null, null, null, null, null, "day");

        assertThat(analytics.summary(firstWeek).byUser().getFirst().documents()).isEqualTo(1);
        assertThat(analytics.summary(AnalyticsQuery.unfiltered()).byUser().getFirst().documents()).isEqualTo(2);
    }

    @Test
    @DisplayName("an instance that has never been used says so, filters or no filters")
    void neverUsedIgnoresTheFilters() {
        // The screen cannot work this out for itself: an empty answer under a date range means
        // either "nothing yet" or "nothing in these dates", and it must not tell somebody to widen
        // a range on an instance where nothing has ever happened.
        AnalyticsQuery lastWeekOnly = new AnalyticsQuery(
                java.time.LocalDateTime.parse("2026-08-01T00:00"),
                java.time.LocalDateTime.parse("2026-08-07T23:59"),
                null, null, null, null, null, "day");

        assertThat(analytics.summary(AnalyticsQuery.unfiltered()).neverUsed()).isTrue();
        assertThat(analytics.summary(lastWeekOnly).neverUsed()).isTrue();
    }

    @Test
    @DisplayName("a filter that excludes everything is not the same as an unused instance")
    void filteredToNothingIsStillAUsedInstance() {
        call("2026-08-01 10:00", "CHAT", danaId, "s1", "OPENAI", "gpt-4o", 100, 10);

        AnalyticsQuery elsewhere = new AnalyticsQuery(
                java.time.LocalDateTime.parse("2026-09-01T00:00"),
                java.time.LocalDateTime.parse("2026-09-30T23:59"),
                null, null, null, null, null, "day");

        AnalyticsSummaryDto summary = analytics.summary(elsewhere);

        assertThat(summary.totals().calls()).isZero();
        assertThat(summary.neverUsed())
                .as("there are records, just not in these dates")
                .isFalse();
    }

    @Test
    @DisplayName("a run with no model call still counts as the instance having been used")
    void aRunAloneIsEnoughToCountAsUsed() {
        // A run that failed before it reached a model writes no usage row at all, and an instance
        // where that is the only thing that ever happened is not a fresh one.
        run("2026-08-01 10:00", "FAILED", danaId, 3);

        assertThat(analytics.summary(AnalyticsQuery.unfiltered()).neverUsed()).isFalse();
    }

    @Test
    @DisplayName("changing a price today does not move what last week cost")
    void historyIsFrozenAtTheRateOfTheDay() {
        // The reason the rate is stored on the call at all. Worked out on every read from the
        // current list, correcting a figure would silently redraw every chart that already had it —
        // an audit whose numbers move without anybody deciding they should is not an audit.
        prices.upsert(new UpsertPriceRequest("OPENAI", "gpt-4o",
                new BigDecimal("2.00"), new BigDecimal("10.00")));
        call("2026-08-01 10:00", "CHAT", danaId, "s1", "OPENAI", "gpt-4o", 1_000_000, 0);

        assertThat(analytics.summary(AnalyticsQuery.unfiltered()).totals().cost())
                .isEqualByComparingTo("2.0000");

        prices.upsert(new UpsertPriceRequest("OPENAI", "gpt-4o",
                new BigDecimal("50.00"), new BigDecimal("99.00")));

        assertThat(analytics.summary(AnalyticsQuery.unfiltered()).totals().cost())
                .as("the call was made at two dollars a million and always will have been")
                .isEqualByComparingTo("2.0000");
    }

    @Test
    @DisplayName("a price change splits one model into the two rates it was called at")
    void bothRatesAreCountedWhenAPriceMovedMidRange() {
        prices.upsert(new UpsertPriceRequest("OPENAI", "gpt-4o",
                new BigDecimal("2.00"), new BigDecimal("10.00")));
        call("2026-08-01 10:00", "CHAT", danaId, "s1", "OPENAI", "gpt-4o", 1_000_000, 0);

        prices.upsert(new UpsertPriceRequest("OPENAI", "gpt-4o",
                new BigDecimal("4.00"), new BigDecimal("10.00")));
        call("2026-08-20 10:00", "CHAT", danaId, "s1", "OPENAI", "gpt-4o", 1_000_000, 0);

        // Two plus four, not two lots of either: grouping collapses the model but never the rate.
        assertThat(analytics.summary(AnalyticsQuery.unfiltered()).totals().cost())
                .isEqualByComparingTo("6.0000");
    }

    @Test
    @DisplayName("a call made before anybody priced the model stays unpriced afterwards")
    void pricingSomethingLaterDoesNotExplainEarlierSpending() {
        call("2026-08-01 10:00", "CHAT", danaId, "s1", "OPENAI", "gpt-4o", 1_000_000, 0);
        prices.upsert(new UpsertPriceRequest("OPENAI", "gpt-4o",
                new BigDecimal("2.00"), new BigDecimal("10.00")));

        AnalyticsSummaryDto summary = analytics.summary(AnalyticsQuery.unfiltered());

        assertThat(summary.totals().cost())
                .as("nobody could account for that call at the time, and a price entered today does not change that")
                .isNull();
        assertThat(summary.unpricedModels()).containsExactly("OPENAI / gpt-4o");
    }

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {

        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:16-alpine");
        }
    }
}
