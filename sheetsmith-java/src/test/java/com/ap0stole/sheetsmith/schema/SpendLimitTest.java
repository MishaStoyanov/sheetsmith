package com.ap0stole.sheetsmith.schema;

import com.ap0stole.sheetsmith.auth.AuthenticatedUser;
import com.ap0stole.sheetsmith.domain.dto.price.UpsertPriceRequest;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.services.BudgetService;
import com.ap0stole.sheetsmith.services.ModelPriceService;
import com.ap0stole.sheetsmith.services.UserService;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a person may spend in a month, and the several ways a limit could quietly mean nothing.
 */
@SpringBootTest
@TestPropertySource(properties = "sheetsmith.auth.enabled=true")
@EnabledIf(value = "dockerAvailable", disabledReason = "Docker is not available")
class SpendLimitTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private BudgetService budgets;

    @Autowired
    private UserService userService;

    @Autowired
    private ModelPriceService prices;

    private Long seededId;
    private Long danaId;
    private Long bossId;
    private Long peerId;

    @BeforeEach
    void seed() {
        jdbc.update("delete from llm_usage");
        jdbc.update("delete from model_prices");
        jdbc.update("delete from users where name like 'budget-%'");

        seededId = jdbc.queryForObject("select min(id) from users", Long.class);
        jdbc.update("update users set role = 'SUPERADMIN' where id = ?", seededId);

        jdbc.update("insert into users (name, password_hash, must_change_password, role) "
                + "values ('budget-dana', 'x', false, 'USER')");
        danaId = jdbc.queryForObject("select id from users where name = 'budget-dana'", Long.class);

        jdbc.update("insert into users (name, password_hash, must_change_password, role) "
                + "values ('budget-boss', 'x', false, 'ADMIN'), ('budget-peer', 'x', false, 'ADMIN')");
        bossId = jdbc.queryForObject("select id from users where name = 'budget-boss'", Long.class);
        peerId = jdbc.queryForObject("select id from users where name = 'budget-peer'", Long.class);

        prices.upsert(new UpsertPriceRequest("OPENAI", "gpt-4o",
                new BigDecimal("2.00"), new BigDecimal("10.00")));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
        jdbc.update("delete from users where name like 'budget-%'");
    }

    private void as(Long id, String name) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthenticatedUser(id, name), null, List.of()));
    }

    /** A call by this person, dated so it lands inside or outside the current month. */
    private void spent(Long userId, String provider, String model, long promptTokens, String when) {
        jdbc.update("""
                insert into llm_usage (kind, user_id, prompt, prompt_tokens, completion_tokens,
                        total_tokens, provider_mode, provider, model, started_at, finished_at)
                values ('CHAT', ?, 'x', ?, 0, ?, 'CLOUD', ?, ?, ?::timestamp, ?::timestamp)
                """, userId, promptTokens, promptTokens, provider, model, when, when);
    }

    private String thisMonth() {
        return LocalDate.now().withDayOfMonth(1).plusDays(1) + " 10:00";
    }

    private String lastMonth() {
        return LocalDate.now().withDayOfMonth(1).minusDays(5) + " 10:00";
    }

    /**
     * Sets a limit the way the application does — as an administrator. Written as a helper because
     * setting one is itself a guarded action, and a test that forgets to be somebody is a test that
     * fails for the wrong reason.
     */
    private void limitFor(Long userId, String amount) {
        as(seededId, "admin");
        userService.setMonthlyBudget(userId, amount == null ? null : new BigDecimal(amount), seededId);
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("no limit means no limit, which is what everybody starts with")
    void noLimitNeverRefuses() {
        as(danaId, "budget-dana");
        spent(danaId, "OPENAI", "gpt-4o", 50_000_000, thisMonth());

        assertThatCode(() -> budgets.requireHeadroom()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("under the limit passes, at the limit refuses")
    void theLimitIsTheLimit() {
        limitFor(danaId, "10.00");
        as(danaId, "budget-dana");

        // Two million prompt tokens at $2 per million is $4.
        spent(danaId, "OPENAI", "gpt-4o", 2_000_000, thisMonth());
        assertThatCode(() -> budgets.requireHeadroom()).doesNotThrowAnyException();

        spent(danaId, "OPENAI", "gpt-4o", 3_000_000, thisMonth());
        assertThatThrownBy(() -> budgets.requireHeadroom())
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("10.00");
    }

    @Test
    @DisplayName("the refusal says how much of what has gone, so it can be acted on")
    void theRefusalCarriesBothNumbers() {
        limitFor(danaId, "5.00");
        as(danaId, "budget-dana");
        spent(danaId, "OPENAI", "gpt-4o", 3_000_000, thisMonth());

        assertThatThrownBy(() -> budgets.requireHeadroom())
                .hasMessageContaining("$6.00")
                .hasMessageContaining("$5.00");
    }

    @Test
    @DisplayName("last month's spending is last month's")
    void theMonthTurnsOver() {
        limitFor(danaId, "1.00");
        as(danaId, "budget-dana");
        spent(danaId, "OPENAI", "gpt-4o", 10_000_000, lastMonth());

        assertThatCode(() -> budgets.requireHeadroom())
                .as("a limit nobody can predict the reset of is a limit nobody plans around")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("one person's spending is not another's")
    void budgetsAreNotShared() {
        limitFor(danaId, "1.00");
        spent(seededId, "OPENAI", "gpt-4o", 10_000_000, thisMonth());
        as(danaId, "budget-dana");

        assertThatCode(() -> budgets.requireHeadroom()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an unpriced model counts as nothing, because unknown is not the same as expensive")
    void unpricedCallsCannotCountTowardsMoney() {
        // The honest reach of a limit denominated in money. Guessing would be worse: a budget
        // enforced against a guess refuses work for a number nobody can check.
        limitFor(danaId, "0.01");
        as(danaId, "budget-dana");
        spent(danaId, "OLLAMA", "gemma4:12b", 500_000_000, thisMonth());
        spent(danaId, "OPENAI", "some-unpriced-preview", 500_000_000, thisMonth());

        assertThat(budgets.spentThisMonth(danaId)).isEqualByComparingTo("0.0000");
        assertThatCode(() -> budgets.requireHeadroom()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("with nobody signed in there is nobody to limit")
    void anonymousCallersAreNotLimited() {
        // Not a loophole: with authentication off there are no accounts at all, and a spend ceiling
        // is a statement about a person.
        limitFor(danaId, "0.01");
        spent(danaId, "OPENAI", "gpt-4o", 10_000_000, thisMonth());

        assertThatCode(() -> budgets.requireHeadroom()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("nobody sets their own limit")
    void noSelfRaising() {
        as(seededId, "admin");

        assertThatThrownBy(() -> userService.setMonthlyBudget(seededId, new BigDecimal("99.00"), seededId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("your own spend limit");
    }

    @Test
    @DisplayName("a plain user cannot set anybody's limit, including through the service directly")
    void plainUsersCannotSetLimits() {
        as(danaId, "budget-dana");

        assertThatThrownBy(() -> userService.setMonthlyBudget(seededId, new BigDecimal("99.00"), danaId))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    @DisplayName("clearing the limit is a value, not an omission")
    void nullClearsIt() {
        limitFor(danaId, "1.00");
        as(danaId, "budget-dana");
        spent(danaId, "OPENAI", "gpt-4o", 10_000_000, thisMonth());
        assertThatThrownBy(() -> budgets.requireHeadroom()).isInstanceOf(ApiException.class);

        limitFor(danaId, null);

        assertThatCode(() -> budgets.requireHeadroom()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the list carries what has been spent beside the limit")
    void theListAnswersBoth() {
        // A ceiling without the current height is a number nobody can act on.
        limitFor(danaId, "10.00");
        spent(danaId, "OPENAI", "gpt-4o", 1_000_000, thisMonth());
        as(seededId, "admin");

        var dana = userService.search(new com.ap0stole.sheetsmith.domain.dto.user.UserSearchRequest(
                        "budget-dana", null, null, null, null))
                .getContent().getFirst();

        assertThat(dana.monthlyBudget()).isEqualByComparingTo("10.00");
        assertThat(dana.spentThisMonth()).isEqualByComparingTo("2.0000");
    }

    // ── Who may see whose money ───────────────────────────────────────────────

    private java.util.Map<String, com.ap0stole.sheetsmith.domain.dto.user.UserDto> listed() {
        return userService.search(new com.ap0stole.sheetsmith.domain.dto.user.UserSearchRequest(
                        "budget-", null, null, null, null))
                .getContent().stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.ap0stole.sheetsmith.domain.dto.user.UserDto::name, u -> u));
    }

    @Test
    @DisplayName("an administrator sees the ordinary users they look after")
    void adminsSeeTheirUsers() {
        limitFor(danaId, "10.00");
        spent(danaId, "OPENAI", "gpt-4o", 1_000_000, thisMonth());
        as(bossId, "budget-boss");

        var dana = listed().get("budget-dana");

        assertThat(dana.spendVisible()).isTrue();
        assertThat(dana.monthlyBudget()).isEqualByComparingTo("10.00");
        assertThat(dana.spentThisMonth()).isEqualByComparingTo("2.0000");
    }

    @Test
    @DisplayName("an administrator does not see a peer administrator's money")
    void adminsDoNotSeeTheirPeers() {
        // "Manages accounts" was never meant to mean "reads the other administrators". The row is
        // built without the figures rather than the screen being trusted to leave them out.
        as(seededId, "admin");
        userService.setMonthlyBudget(peerId, new BigDecimal("50.00"), seededId);
        SecurityContextHolder.clearContext();

        as(bossId, "budget-boss");
        var peer = listed().get("budget-peer");

        assertThat(peer.spendVisible()).isFalse();
        assertThat(peer.monthlyBudget()).as("the ceiling is hidden with the spending").isNull();
        assertThat(peer.spentThisMonth()).isNull();
    }

    @Test
    @DisplayName("everybody sees their own, whatever their role")
    void yourOwnIsAlwaysYours() {
        as(seededId, "admin");
        userService.setMonthlyBudget(bossId, new BigDecimal("20.00"), seededId);
        SecurityContextHolder.clearContext();

        as(bossId, "budget-boss");

        assertThat(listed().get("budget-boss").spendVisible()).isTrue();
        assertThat(userService.mySpend(bossId).monthlyBudget()).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("the superadmin sees everyone, administrators included")
    void theSuperadminSeesEverybody() {
        as(seededId, "admin");
        userService.setMonthlyBudget(peerId, new BigDecimal("50.00"), seededId);

        var all = listed();

        assertThat(all.get("budget-peer").spendVisible()).isTrue();
        assertThat(all.get("budget-dana").spendVisible()).isTrue();
    }

    @Test
    @DisplayName("a plain user sees nobody else, not even another plain user")
    void plainUsersSeeOnlyThemselves() {
        limitFor(danaId, "10.00");
        as(danaId, "budget-dana");

        var all = listed();

        assertThat(all.get("budget-dana").spendVisible()).isTrue();
        assertThat(all.get("budget-boss").spendVisible()).isFalse();
    }

    @Test
    @DisplayName("your own figures are readable without reaching the accounts screen")
    void mySpendAnswersTheOneWhoCannotSeeTheList() {
        // The whole point of the separate call: a plain user has no business on the accounts screen,
        // and telling somebody they have run out without ever showing them the gauge is a limit
        // people resent rather than plan around.
        limitFor(danaId, "10.00");
        spent(danaId, "OPENAI", "gpt-4o", 2_000_000, thisMonth());
        as(danaId, "budget-dana");

        var mine = userService.mySpend(danaId);

        assertThat(mine.visible()).isTrue();
        assertThat(mine.monthlyBudget()).isEqualByComparingTo("10.00");
        assertThat(mine.spentThisMonth()).isEqualByComparingTo("4.0000");
    }

    @Test
    @DisplayName("no limit still reports what has been spent")
    void spendingIsWorthKnowingWithoutACeiling() {
        spent(danaId, "OPENAI", "gpt-4o", 1_000_000, thisMonth());
        as(danaId, "budget-dana");

        var mine = userService.mySpend(danaId);

        assertThat(mine.monthlyBudget()).isNull();
        assertThat(mine.spentThisMonth())
                .as("\"no limit\" is not the same as \"nothing spent\"")
                .isEqualByComparingTo("2.0000");
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
