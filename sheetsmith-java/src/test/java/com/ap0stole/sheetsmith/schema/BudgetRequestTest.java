package com.ap0stole.sheetsmith.schema;

import com.ap0stole.sheetsmith.auth.AuthenticatedUser;
import com.ap0stole.sheetsmith.domain.dto.price.UpsertPriceRequest;
import com.ap0stole.sheetsmith.domain.entity.BudgetRequest;
import com.ap0stole.sheetsmith.domain.enums.BudgetRequestStatus;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.services.BudgetRequestService;
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
import org.springframework.security.access.AccessDeniedException;
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
 * Asking for a bigger ceiling, and the several ways the answer could mislead somebody.
 */
@SpringBootTest
@TestPropertySource(properties = "sheetsmith.auth.enabled=true")
@EnabledIf(value = "dockerAvailable", disabledReason = "Docker is not available")
class BudgetRequestTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private BudgetRequestService budgetRequests;

    @Autowired
    private UserService userService;

    @Autowired
    private ModelPriceService prices;

    private Long seededId;
    private Long danaId;
    private Long bossId;

    @BeforeEach
    void seed() {
        jdbc.update("delete from budget_requests");
        jdbc.update("delete from llm_usage");
        jdbc.update("delete from model_prices");
        jdbc.update("delete from users where name like 'ask-%'");

        seededId = jdbc.queryForObject("select min(id) from users", Long.class);
        jdbc.update("update users set role = 'SUPERADMIN', monthly_budget = null where id = ?", seededId);

        jdbc.update("insert into users (name, password_hash, must_change_password, role) values "
                + "('ask-dana', 'x', false, 'USER'), ('ask-boss', 'x', false, 'ADMIN')");
        danaId = jdbc.queryForObject("select id from users where name = 'ask-dana'", Long.class);
        bossId = jdbc.queryForObject("select id from users where name = 'ask-boss'", Long.class);

        // Signed in to seed it: writing a price is the superadmin's act now, the same as deleting
        // one, and a fixture that could set prices as nobody would be testing a door that is shut.
        as(seededId, "admin");
        prices.upsert(new UpsertPriceRequest("OPENAI", "gpt-4o",
                new BigDecimal("2.00"), new BigDecimal("10.00")));
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tidy() {
        SecurityContextHolder.clearContext();
        jdbc.update("delete from budget_requests");
        jdbc.update("delete from users where name like 'ask-%'");
    }

    private void as(Long id, String name) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthenticatedUser(id, name), null, List.of()));
    }

    /** Spends this many dollars for dana, at $2 per million prompt tokens. */
    private void spend(String dollars) {
        long tokens = new BigDecimal(dollars).divide(new BigDecimal("2.00")).movePointRight(6).longValue();
        jdbc.update("""
                insert into llm_usage (kind, user_id, prompt, prompt_tokens, completion_tokens,
                        total_tokens, provider_mode, provider, model,
                        input_per_million, output_per_million, started_at, finished_at)
                select 'CHAT', ?, 'x', ?, 0, ?, 'CLOUD', 'OPENAI', 'gpt-4o',
                       p.input_per_million, p.output_per_million, ?::timestamp, ?::timestamp
                from (select 1) as one
                left join model_prices p on p.provider = 'OPENAI' and p.model = 'gpt-4o'
                """, danaId, tokens, tokens,
                LocalDate.now().withDayOfMonth(1).plusDays(1) + " 10:00",
                LocalDate.now().withDayOfMonth(1).plusDays(1) + " 10:00");
    }

    private void limitFor(Long userId, String amount) {
        as(seededId, "admin");
        userService.setMonthlyBudget(userId, new BigDecimal(amount), seededId);
        SecurityContextHolder.clearContext();
    }

    // ── When asking is offered ────────────────────────────────────────────────

    @Test
    @DisplayName("with room to spare there is nothing to ask about")
    void nothingToAskWithRoomLeft() {
        limitFor(danaId, "10.00");
        spend("5.00");
        as(danaId, "ask-dana");

        assertThat(budgetRequests.mayAsk(danaId)).isFalse();
        assertThatThrownBy(() -> budgetRequests.ask(danaId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("still have room");
    }

    @Test
    @DisplayName("the last fifteen per cent is where asking starts")
    void askingOpensAtEightyFive() {
        limitFor(danaId, "10.00");
        spend("8.40");
        as(danaId, "ask-dana");
        assertThat(budgetRequests.mayAsk(danaId)).isFalse();

        spend("0.20");
        assertThat(budgetRequests.mayAsk(danaId))
                .as("$8.60 of $10.00 is past the last fifteen per cent")
                .isTrue();
    }

    @Test
    @DisplayName("no limit means no ceiling to raise")
    void noLimitNothingToRaise() {
        as(danaId, "ask-dana");

        assertThat(budgetRequests.mayAsk(danaId)).isFalse();
        assertThatThrownBy(() -> budgetRequests.ask(danaId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no spend limit");
    }

    @Test
    @DisplayName("one request at a time")
    void oneAtATime() {
        limitFor(danaId, "10.00");
        spend("9.00");
        as(danaId, "ask-dana");
        budgetRequests.ask(danaId);

        assertThatThrownBy(() -> budgetRequests.ask(danaId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already have a request");
    }

    // ── Answering ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("approving raises the limit and records what it became")
    void approvingRaisesIt() {
        limitFor(danaId, "10.00");
        spend("9.00");
        as(danaId, "ask-dana");
        BudgetRequest asked = budgetRequests.ask(danaId);

        as(bossId, "ask-boss");
        BudgetRequest decided = budgetRequests.decide(asked.getId(), true, new BigDecimal("25.00"), bossId);

        assertThat(decided.getStatus()).isEqualTo(BudgetRequestStatus.APPROVED);
        assertThat(decided.getNewLimit()).isEqualByComparingTo("25.00");
        assertThat(jdbc.queryForObject("select monthly_budget from users where id = ?", BigDecimal.class, danaId))
                .isEqualByComparingTo("25.00");
    }

    @Test
    @DisplayName("an approval that does not raise anything is refused rather than sent as a lie")
    void approvingMustActuallyRaise() {
        // The person is told their limit was increased. That has to be true, or the next
        // notification is one nobody reads.
        limitFor(danaId, "10.00");
        spend("9.00");
        as(danaId, "ask-dana");
        BudgetRequest asked = budgetRequests.ask(danaId);

        as(bossId, "ask-boss");

        assertThatThrownBy(() -> budgetRequests.decide(asked.getId(), true, new BigDecimal("10.00"), bossId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("has to be higher");
        assertThatThrownBy(() -> budgetRequests.decide(asked.getId(), true, null, bossId))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("declining leaves the limit exactly where it was")
    void decliningChangesNothing() {
        limitFor(danaId, "10.00");
        spend("9.00");
        as(danaId, "ask-dana");
        BudgetRequest asked = budgetRequests.ask(danaId);

        as(bossId, "ask-boss");
        BudgetRequest decided = budgetRequests.decide(asked.getId(), false, null, bossId);

        assertThat(decided.getStatus()).isEqualTo(BudgetRequestStatus.DECLINED);
        assertThat(decided.getNewLimit()).isNull();
        assertThat(jdbc.queryForObject("select monthly_budget from users where id = ?", BigDecimal.class, danaId))
                .isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("a request cannot be answered twice")
    void answeredOnce() {
        limitFor(danaId, "10.00");
        spend("9.00");
        as(danaId, "ask-dana");
        BudgetRequest asked = budgetRequests.ask(danaId);

        as(bossId, "ask-boss");
        budgetRequests.decide(asked.getId(), false, null, bossId);

        assertThatThrownBy(() -> budgetRequests.decide(asked.getId(), true, new BigDecimal("50.00"), bossId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already been answered");
    }

    @Test
    @DisplayName("nobody answers their own request")
    void noSelfApproval() {
        limitFor(bossId, "10.00");
        jdbc.update("update llm_usage set user_id = ? where user_id = ?", bossId, danaId);
        spend("9.00");
        jdbc.update("update llm_usage set user_id = ? where user_id = ?", bossId, danaId);

        as(bossId, "ask-boss");
        BudgetRequest asked = budgetRequests.ask(bossId);

        assertThatThrownBy(() -> budgetRequests.decide(asked.getId(), true, new BigDecimal("50.00"), bossId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("your own request");
    }

    @Test
    @DisplayName("a plain user cannot answer anybody, including through the service directly")
    void plainUsersCannotDecide() {
        limitFor(danaId, "10.00");
        spend("9.00");
        as(danaId, "ask-dana");
        BudgetRequest asked = budgetRequests.ask(danaId);

        assertThatThrownBy(() -> budgetRequests.decide(asked.getId(), true, new BigDecimal("50.00"), danaId))
                .isInstanceOf(ApiException.class);
        // Who may read the queue at all is a rule about the endpoint rather than about a row, so it
        // sits on the handler and is asked there: see SecurityMatrixTest.
    }

    // ── Being told ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("both outcomes are worth telling somebody about")
    void bothOutcomesNotify() {
        limitFor(danaId, "10.00");
        spend("9.00");
        as(danaId, "ask-dana");
        BudgetRequest asked = budgetRequests.ask(danaId);
        as(bossId, "ask-boss");
        budgetRequests.decide(asked.getId(), false, null, bossId);

        as(danaId, "ask-dana");

        assertThat(budgetRequests.undeliveredDecisionFor(danaId))
                .as("a refusal somebody is never told about is a request that vanished")
                .isPresent();
    }

    @Test
    @DisplayName("the notification happens once")
    void toldOnlyOnce() {
        limitFor(danaId, "10.00");
        spend("9.00");
        as(danaId, "ask-dana");
        BudgetRequest asked = budgetRequests.ask(danaId);
        as(bossId, "ask-boss");
        budgetRequests.decide(asked.getId(), true, new BigDecimal("30.00"), bossId);

        as(danaId, "ask-dana");
        assertThat(budgetRequests.undeliveredDecisionFor(danaId)).isPresent();

        budgetRequests.markSeen(danaId);

        assertThat(budgetRequests.undeliveredDecisionFor(danaId)).isEmpty();
    }

    @Test
    @DisplayName("dismissing nothing is not an error")
    void dismissingNothingIsFine() {
        as(danaId, "ask-dana");

        assertThatCode(() -> budgetRequests.markSeen(danaId)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an approved request remembers the figure, not whatever the limit is now")
    void theRecordKeepsItsOwnNumber() {
        // The limit moves again afterwards — that is the point of it — so a message built from
        // today's value would eventually describe a decision that never happened.
        limitFor(danaId, "10.00");
        spend("9.00");
        as(danaId, "ask-dana");
        BudgetRequest asked = budgetRequests.ask(danaId);
        as(bossId, "ask-boss");
        budgetRequests.decide(asked.getId(), true, new BigDecimal("30.00"), bossId);

        userService.setMonthlyBudget(danaId, new BigDecimal("12.00"), bossId);

        as(danaId, "ask-dana");
        assertThat(budgetRequests.undeliveredDecisionFor(danaId).orElseThrow().getNewLimit())
                .isEqualByComparingTo("30.00");
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
