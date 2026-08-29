package com.ap0stole.sheetsmith.schema;

import com.ap0stole.sheetsmith.auth.AuthenticatedUser;
import com.ap0stole.sheetsmith.domain.dto.prompt.FrequentPromptDto;
import com.ap0stole.sheetsmith.domain.enums.UsageKind;
import com.ap0stole.sheetsmith.services.PromptHistoryService;
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
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What you asked for before — and, more importantly, what nobody else gets to see.
 */
@SpringBootTest
@EnabledIf(value = "dockerAvailable", disabledReason = "Docker is not available")
class FrequentPromptsTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PromptHistoryService prompts;

    private Long danaId;
    private Long samId;

    @BeforeEach
    void seed() {
        jdbc.update("delete from llm_usage");
        jdbc.update("delete from users where name in ('prompt-dana', 'prompt-sam')");
        jdbc.update("insert into users (name, password_hash) values ('prompt-dana', 'x'), ('prompt-sam', 'x')");
        danaId = jdbc.queryForObject("select id from users where name = 'prompt-dana'", Long.class);
        samId = jdbc.queryForObject("select id from users where name = 'prompt-sam'", Long.class);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private void signedInAs(Long id, String name) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthenticatedUser(id, name), null, List.of()));
    }

    private void asked(Long userId, UsageKind kind, String prompt, String when) {
        jdbc.update("""
                insert into llm_usage (kind, user_id, prompt, total_tokens, provider_mode, provider,
                        model, started_at, finished_at)
                values (?, ?, ?, 10, 'CLOUD', 'OPENAI', 'gpt-4o', ?::timestamp, ?::timestamp)
                """, kind.name(), userId, prompt, when, when);
    }

    @Test
    @DisplayName("a phrasing used once is not a habit and is not offered")
    void oneUseIsNotASuggestion() {
        signedInAs(danaId, "prompt-dana");
        asked(danaId, UsageKind.IMPROVE, "tidy the header row", "2026-08-01 10:00");
        asked(danaId, UsageKind.IMPROVE, "sort by revenue", "2026-08-02 10:00");
        asked(danaId, UsageKind.IMPROVE, "sort by revenue", "2026-08-03 10:00");

        assertThat(prompts.frequent(UsageKind.IMPROVE, 5))
                .extracting(FrequentPromptDto::text)
                .containsExactly("sort by revenue");
    }

    @Test
    @DisplayName("one person never sees another's prompts")
    void promptsAreNeverSharedBetweenPeople() {
        // The one thing on this instance that is genuinely private: a prompt is somebody describing
        // their own data in their own words.
        asked(samId, UsageKind.IMPROVE, "reconcile the Novartis invoices", "2026-08-01 10:00");
        asked(samId, UsageKind.IMPROVE, "reconcile the Novartis invoices", "2026-08-02 10:00");
        asked(danaId, UsageKind.IMPROVE, "sort by revenue", "2026-08-02 10:00");
        asked(danaId, UsageKind.IMPROVE, "sort by revenue", "2026-08-03 10:00");

        signedInAs(danaId, "prompt-dana");

        assertThat(prompts.frequent(UsageKind.IMPROVE, 5))
                .extracting(FrequentPromptDto::text)
                .containsExactly("sort by revenue");
    }

    @Test
    @DisplayName("signed in, the prompts of the instance's ownerless past stay out of it")
    void ownerlessPromptsAreNotHandedToWhoeverSignsIn() {
        asked(null, UsageKind.IMPROVE, "from before there were accounts", "2026-08-01 10:00");
        asked(null, UsageKind.IMPROVE, "from before there were accounts", "2026-08-02 10:00");

        signedInAs(danaId, "prompt-dana");

        assertThat(prompts.frequent(UsageKind.IMPROVE, 5)).isEmpty();
    }

    @Test
    @DisplayName("with no accounts at all, the ownerless prompts are yours by construction")
    void withoutAccountsTheUnownedPromptsAreYours() {
        asked(null, UsageKind.IMPROVE, "tidy it up", "2026-08-01 10:00");
        asked(null, UsageKind.IMPROVE, "tidy it up", "2026-08-02 10:00");
        asked(samId, UsageKind.IMPROVE, "somebody else's", "2026-08-01 10:00");
        asked(samId, UsageKind.IMPROVE, "somebody else's", "2026-08-02 10:00");

        assertThat(prompts.frequent(UsageKind.IMPROVE, 5))
                .extracting(FrequentPromptDto::text)
                .containsExactly("tidy it up");
    }

    @Test
    @DisplayName("the two flows are asked about separately")
    void chatAndImproveAreDifferentQuestions() {
        signedInAs(danaId, "prompt-dana");
        asked(danaId, UsageKind.CHAT, "widen column C", "2026-08-01 10:00");
        asked(danaId, UsageKind.CHAT, "widen column C", "2026-08-02 10:00");
        asked(danaId, UsageKind.IMPROVE, "sort by revenue", "2026-08-01 10:00");
        asked(danaId, UsageKind.IMPROVE, "sort by revenue", "2026-08-02 10:00");

        assertThat(prompts.frequent(UsageKind.CHAT, 5)).extracting(FrequentPromptDto::text)
                .containsExactly("widen column C");
        assertThat(prompts.frequent(UsageKind.IMPROVE, 5)).extracting(FrequentPromptDto::text)
                .containsExactly("sort by revenue");
    }

    @Test
    @DisplayName("the most used comes first, and the most recent breaks a tie")
    void rankedByUseThenRecency() {
        signedInAs(danaId, "prompt-dana");
        asked(danaId, UsageKind.IMPROVE, "three times", "2026-08-01 10:00");
        asked(danaId, UsageKind.IMPROVE, "three times", "2026-08-01 11:00");
        asked(danaId, UsageKind.IMPROVE, "three times", "2026-08-01 12:00");
        asked(danaId, UsageKind.IMPROVE, "older pair", "2026-08-01 09:00");
        asked(danaId, UsageKind.IMPROVE, "older pair", "2026-08-02 09:00");
        asked(danaId, UsageKind.IMPROVE, "newer pair", "2026-08-05 09:00");
        asked(danaId, UsageKind.IMPROVE, "newer pair", "2026-08-06 09:00");

        assertThat(prompts.frequent(UsageKind.IMPROVE, 5)).extracting(FrequentPromptDto::text)
                .containsExactly("three times", "newer pair", "older pair");
    }

    @Test
    @DisplayName("blank prompts are not a phrasing anybody wants offered back")
    void emptyPromptsAreSkipped() {
        signedInAs(danaId, "prompt-dana");
        asked(danaId, UsageKind.IMPROVE, "   ", "2026-08-01 10:00");
        asked(danaId, UsageKind.IMPROVE, "   ", "2026-08-02 10:00");
        asked(danaId, UsageKind.IMPROVE, null, "2026-08-03 10:00");
        asked(danaId, UsageKind.IMPROVE, null, "2026-08-04 10:00");

        assertThat(prompts.frequent(UsageKind.IMPROVE, 5)).isEmpty();
    }

    @Test
    @DisplayName("the limit is capped rather than trusted")
    void theLimitIsCapped() {
        signedInAs(danaId, "prompt-dana");
        for (int i = 0; i < 25; i++) {
            asked(danaId, UsageKind.IMPROVE, "phrasing " + i, "2026-08-01 10:00");
            asked(danaId, UsageKind.IMPROVE, "phrasing " + i, "2026-08-02 10:00");
        }

        assertThat(prompts.frequent(UsageKind.IMPROVE, 5_000)).hasSize(20);
        assertThat(prompts.frequent(UsageKind.IMPROVE, 0)).hasSize(1);
    }

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable _) {
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
