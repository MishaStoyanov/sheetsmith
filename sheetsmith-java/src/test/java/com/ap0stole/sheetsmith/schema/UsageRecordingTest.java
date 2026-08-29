package com.ap0stole.sheetsmith.schema;

import com.ap0stole.sheetsmith.domain.entity.LlmUsage;
import com.ap0stole.sheetsmith.domain.entity.User;
import com.ap0stole.sheetsmith.domain.enums.UsageKind;
import com.ap0stole.sheetsmith.llm.LlmEngine;
import com.ap0stole.sheetsmith.llm.TokenUsage;
import com.ap0stole.sheetsmith.repository.LlmUsageRepository;
import com.ap0stole.sheetsmith.repository.UserRepository;
import com.ap0stole.sheetsmith.services.UsageRecorder;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a model call is written down, and that both flows land in the same place.
 * <p>
 * The point of one table is that "how much was spent" is one sum. A test that only checked the
 * improve side would pass on the arrangement this replaces — where chat spent money and recorded
 * nothing, and the resulting chart looked entirely plausible.
 */
@SpringBootTest
@EnabledIf(value = "dockerAvailable", disabledReason = "Docker is not available")
class UsageRecordingTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UsageRecorder recorder;

    @Autowired
    private LlmUsageRepository usage;

    @Autowired
    private UserRepository users;

    private User dana;

    @BeforeEach
    void seed() {
        jdbc.update("delete from llm_usage");
        jdbc.update("delete from users where name = 'usage-fixture'");
        jdbc.update("insert into users (name, password_hash) values ('usage-fixture', 'x')");
        dana = users.findByName("usage-fixture").orElseThrow();
    }

    @Test
    @DisplayName("a chat step and an improve plan sum as one number")
    void bothFlowsLandInOneTable() {
        recorder.chatlessPlan("session-1", dana, "tidy the columns",
                new TokenUsage(1000L, 200L, 1200L), new LlmEngine("CLOUD", "OPENAI", "gpt-4o"),
                LocalDateTime.now());
        recorder.chat("session-1", dana, "what is the total?",
                new TokenUsage(300L, 40L, 340L), new LlmEngine("CLOUD", "OPENAI", "gpt-4o"),
                LocalDateTime.now());

        Long total = jdbc.queryForObject("select sum(total_tokens) from llm_usage", Long.class);

        assertThat(total).isEqualTo(1540L);
        assertThat(usage.findAll()).extracting(LlmUsage::getKind)
                .containsExactlyInAnyOrder(UsageKind.IMPROVE, UsageKind.CHAT);
    }

    @Test
    @DisplayName("a call keeps who asked, what they asked and which engine answered")
    void aRowCarriesTheWholeStory() {
        recorder.chat("session-2", dana, "make the header bold",
                new TokenUsage(90L, 10L, 100L), new LlmEngine("LOCAL", "OLLAMA", "gemma4:12b"),
                LocalDateTime.now().minusSeconds(3));

        LlmUsage row = usage.findAll().getFirst();

        // The id, not the name: the owner is a lazy relation and nothing here needs to load it.
        // Analytics aggregates in SQL rather than walking entities, so there is no fetch join to add.
        assertThat(row.getUser().getId()).isEqualTo(dana.getId());
        assertThat(row.getPrompt()).isEqualTo("make the header bold");
        assertThat(row.getProvider()).isEqualTo("OLLAMA");
        assertThat(row.getModel()).isEqualTo("gemma4:12b");
        assertThat(row.getFinishedAt()).isAfter(row.getStartedAt());
    }

    @Test
    @DisplayName("nobody signed in is recorded as nobody, not skipped")
    void anUnownedCallIsStillRecorded() {
        // On an instance with no accounts every call has no owner. Dropping those rows would leave
        // the spend chart empty on exactly the instances most people run.
        recorder.chat("session-3", null, "tidy it",
                new TokenUsage(50L, 5L, 55L), new LlmEngine("LOCAL", "OLLAMA", "llama3.1"),
                LocalDateTime.now());

        List<LlmUsage> rows = usage.findAll();

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getUser()).isNull();
    }

    @Test
    @DisplayName("a provider that reported nothing still leaves a row for the call")
    void silenceStillCountsAsACall() {
        // The call happened whether or not anyone said what it cost. Counting calls and counting
        // tokens are different questions, and only one of them is unanswerable here.
        recorder.chat("session-4", dana, "what changed?", TokenUsage.NONE,
                new LlmEngine("LOCAL", "OLLAMA", "gemma4:12b"), LocalDateTime.now());

        LlmUsage row = usage.findAll().getFirst();

        assertThat(row.getTotalTokens()).isNull();
        assertThat(row.getModel()).isEqualTo("gemma4:12b");
    }

    @Test
    @DisplayName("deleting the person keeps the record that the money was spent")
    void deletingAUserKeepsTheSpend() {
        recorder.chat("session-5", dana, "tidy it", new TokenUsage(10L, 1L, 11L),
                new LlmEngine("LOCAL", "OLLAMA", "llama3.1"), LocalDateTime.now());

        jdbc.update("delete from users where name = 'usage-fixture'");

        assertThat(usage.findAll()).hasSize(1);
        assertThat(jdbc.queryForObject("select user_id from llm_usage", Long.class)).isNull();
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
