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
        jdbc.update("delete from llm_usage");
        jdbc.update("delete from model_prices");
        jdbc.update("delete from users where name = 'analytics-fixture'");
        jdbc.update("insert into users (name, password_hash) values ('analytics-fixture', 'x')");
        danaId = jdbc.queryForObject("select id from users where name = 'analytics-fixture'", Long.class);
    }

    /** Written straight in, so the timestamps and the owner are exactly what each case needs. */
    private void call(String when, String kind, Long userId, String session,
                      String provider, String model, long prompt, long completion) {
        jdbc.update("""
                insert into llm_usage (kind, user_id, session_id, prompt, prompt_tokens,
                        completion_tokens, total_tokens, provider_mode, provider, model,
                        started_at, finished_at)
                values (?, ?, ?, 'tidy it', ?, ?, ?, 'CLOUD', ?, ?, ?::timestamp, ?::timestamp)
                """, kind, userId, session, prompt, completion, prompt + completion, provider, model, when, when);
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
