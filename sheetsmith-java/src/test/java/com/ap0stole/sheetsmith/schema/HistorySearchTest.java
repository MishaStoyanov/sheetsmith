package com.ap0stole.sheetsmith.schema;

import com.ap0stole.sheetsmith.domain.dto.HistorySearchRequest;
import com.ap0stole.sheetsmith.domain.dto.JobHistoryDto;
import com.ap0stole.sheetsmith.domain.enums.JobStatus;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.services.JobService;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The history filters, against a real database rather than a mock.
 * <p>
 * They have to run here: the duration filter is expressed in SQL functions, the owner filter joins
 * a relation, and a paged count is the count of what matched rather than of what was read. A
 * repository stubbed in Java would agree with whatever the test expected and prove none of it.
 */
@SpringBootTest
@EnabledIf(value = "dockerAvailable", disabledReason = "Docker is not available")
class HistorySearchTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JobService jobService;

    private Long danaId;

    @BeforeEach
    void seed() {
        jdbc.update("delete from job_records");
        jdbc.update("delete from users where name = 'dana'");

        jdbc.update("insert into users (name, password_hash) values ('dana', 'x')");
        danaId = jdbc.queryForObject("select id from users where name = 'dana'", Long.class);

        insert("2026-08-01 10:00", "COMPLETED", "quarterly.xlsx", "bold the header", danaId, 1500L, "OPENAI", "gpt-4o", 30);
        insert("2026-08-10 10:00", "FAILED", "broken.xlsx", "fix the totals", danaId, 400L, "OPENAI", "gpt-4o", 2);
        insert("2026-08-20 10:00", "COMPLETED", "orders.xlsx", "tidy the columns", null, null, "OLLAMA", "gemma4:12b", 120);
    }

    private void insert(String created, String status, String file, String instruction,
                        Long owner, Long tokens, String provider, String model, int seconds) {
        jdbc.update("""
                insert into job_records (created_at, processing_started_at, processing_finished_at,
                        instruction, input_filename, input_file_path, status, user_id,
                        total_tokens, provider, model)
                values (?::timestamp, ?::timestamp, ?::timestamp + (? * interval '1 second'),
                        ?, ?, '/tmp/x.xlsx', ?, ?, ?, ?, ?)
                """, created, created, created, seconds, instruction, file, status, owner, tokens, provider, model);
    }

    private List<String> filesFrom(HistorySearchRequest request) {
        return jobService.search(request).getContent().stream().map(JobHistoryDto::getInputFilename).toList();
    }

    private HistorySearchRequest with(HistorySearchRequest base) {
        return base;
    }

    @Test
    @DisplayName("no filters is the whole history, newest first")
    void unfilteredIsEverything() {
        assertThat(filesFrom(HistorySearchRequest.unfiltered()))
                .containsExactly("orders.xlsx", "broken.xlsx", "quarterly.xlsx");
    }

    @Test
    @DisplayName("the keyword looks at the instruction and the filename, not one of them")
    void keywordSearchesBothColumns() {
        assertThat(filesFrom(new HistorySearchRequest("QUARTER", null, null, null, null, null, null,
                null, null, null, null, null, null, null, null)))
                .containsExactly("quarterly.xlsx");

        assertThat(filesFrom(new HistorySearchRequest("totals", null, null, null, null, null, null,
                null, null, null, null, null, null, null, null)))
                .containsExactly("broken.xlsx");
    }

    @Test
    @DisplayName("a date range takes both ends")
    void dateRangeNarrows() {
        var from = java.time.LocalDateTime.parse("2026-08-05T00:00");
        var to = java.time.LocalDateTime.parse("2026-08-15T00:00");

        assertThat(filesFrom(new HistorySearchRequest(null, from, to, null, null, null, null,
                null, null, null, null, null, null, null, null)))
                .containsExactly("broken.xlsx");
    }

    @Test
    @DisplayName("runs nobody owns are reachable, or most of a history would not be")
    void unownedRunsCanBeAskedFor() {
        // On an instance that never turned authentication on, "no owner" is every run there is.
        // Filtering only by user id would make them unreachable through the owner filter entirely.
        assertThat(filesFrom(new HistorySearchRequest(null, null, null, null, true, null, null,
                null, null, null, null, null, null, null, null)))
                .containsExactly("orders.xlsx");
    }

    @Test
    @DisplayName("a named owner and nobody can be asked for together")
    void ownerAndUnownedCombine() {
        assertThat(filesFrom(new HistorySearchRequest(null, null, null, List.of(danaId), true, null, null,
                null, null, null, null, null, null, null, null)))
                .containsExactly("orders.xlsx", "broken.xlsx", "quarterly.xlsx");

        assertThat(filesFrom(new HistorySearchRequest(null, null, null, List.of(danaId), false, null, null,
                null, null, null, null, null, null, null, null)))
                .containsExactly("broken.xlsx", "quarterly.xlsx");
    }

    @Test
    @DisplayName("status, provider and token filters each narrow on their own")
    void theSimpleFiltersWork() {
        assertThat(filesFrom(new HistorySearchRequest(null, null, null, null, null,
                List.of(JobStatus.FAILED), null, null, null, null, null, null, null, null, null)))
                .containsExactly("broken.xlsx");

        assertThat(filesFrom(new HistorySearchRequest(null, null, null, null, null, null,
                List.of("OLLAMA"), null, null, null, null, null, null, null, null)))
                .containsExactly("orders.xlsx");

        assertThat(filesFrom(new HistorySearchRequest(null, null, null, null, null, null, null, null,
                null, 1000L, null, null, null, null, null)))
                .containsExactly("quarterly.xlsx");
    }

    @Test
    @DisplayName("the duration filter measures what the run took, in the database")
    void durationFilterWorks() {
        // 30s, 2s and 120s: asking for a minute or more leaves one.
        assertThat(filesFrom(new HistorySearchRequest(null, null, null, null, null, null, null, null,
                60_000L, null, null, null, null, null, null)))
                .containsExactly("orders.xlsx");
    }

    @Test
    @DisplayName("sorting is an allowlist, and the owner is sorted by name not by id")
    void sortingIsConstrained() {
        assertThat(filesFrom(new HistorySearchRequest(null, null, null, null, null, null, null, null,
                null, null, null, null, null, "inputFilename", "asc")))
                .containsExactly("broken.xlsx", "orders.xlsx", "quarterly.xlsx");

        // A Sort built from the caller's string reaches any property of the entity and its
        // relations — including the owner's password hash, whose order alone would say something.
        assertThatThrownBy(() -> jobService.search(new HistorySearchRequest(null, null, null, null, null,
                null, null, null, null, null, null, null, null, "startedBy.passwordHash", "asc")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Cannot sort runs by");
    }

    @Test
    @DisplayName("the page count counts what matched, not what was read")
    void pagingCountsTheFilteredSet() {
        var page = jobService.search(new HistorySearchRequest(null, null, null, null, null,
                List.of(JobStatus.COMPLETED), null, null, null, null, null, 0, 1, null, null));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getTotalPages()).isEqualTo(2);
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
