package com.ap0stole.sheetsmith.schema;

import com.ap0stole.sheetsmith.auth.AuthenticatedUser;
import com.ap0stole.sheetsmith.domain.dto.HistorySearchRequest;
import com.ap0stole.sheetsmith.domain.dto.JobHistoryDto;
import com.ap0stole.sheetsmith.domain.dto.analytics.AnalyticsQuery;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.services.AnalyticsService;
import com.ap0stole.sheetsmith.services.JobService;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Whose work each role can see, and who is allowed to remove any of it.
 * <p>
 * Both halves of one ladder, tested together because they are the same rule read twice: the history
 * and the analytics answer one question about somebody's work, and deletion is the one answer that
 * cannot be given back.
 */
@SpringBootTest
@TestPropertySource(properties = "sheetsmith.auth.enabled=true")
@EnabledIf(value = "dockerAvailable", disabledReason = "Docker is not available")
class WorkVisibilityTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JobService jobService;

    @Autowired
    private AnalyticsService analytics;

    private Long bossId;
    private Long peerId;
    private Long danaId;
    private Long samId;
    private Long superId;

    @BeforeEach
    void seed() {
        jdbc.update("delete from llm_usage");
        jdbc.update("delete from job_records");
        jdbc.update("delete from users where name like 'vis-%'");

        superId = jdbc.queryForObject("select min(id) from users", Long.class);
        jdbc.update("update users set role = 'SUPERADMIN' where id = ?", superId);

        jdbc.update("""
                insert into users (name, password_hash, must_change_password, role) values
                    ('vis-boss', 'x', false, 'ADMIN'),
                    ('vis-peer', 'x', false, 'ADMIN'),
                    ('vis-dana', 'x', false, 'USER'),
                    ('vis-sam',  'x', false, 'USER')
                """);
        bossId = idOf("vis-boss");
        peerId = idOf("vis-peer");
        danaId = idOf("vis-dana");
        samId = idOf("vis-sam");

        run("boss.xlsx", bossId);
        run("peer.xlsx", peerId);
        run("dana.xlsx", danaId);
        run("sam.xlsx", samId);
        run("super.xlsx", superId);
        run("nobody.xlsx", null);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
        jdbc.update("delete from job_records");
        jdbc.update("delete from users where name like 'vis-%'");
    }

    private Long idOf(String name) {
        return jdbc.queryForObject("select id from users where name = ?", Long.class, name);
    }

    private void run(String file, Long owner) {
        jdbc.update("""
                insert into job_records (created_at, instruction, input_filename, input_file_path,
                        status, user_id, total_tokens, provider, model)
                values (now(), 'tidy it', ?, '/tmp/x.xlsx', 'COMPLETED', ?, 1000, 'OPENAI', 'gpt-4o')
                """, file, owner);
        jdbc.update("""
                insert into llm_usage (kind, user_id, prompt, prompt_tokens, completion_tokens,
                        total_tokens, provider_mode, provider, model, started_at, finished_at)
                values ('CHAT', ?, 'tidy it', 1000, 0, 1000, 'CLOUD', 'OPENAI', 'gpt-4o', now(), now())
                """, owner);
    }

    private void as(Long id, String name) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthenticatedUser(id, name), null, List.of()));
    }

    private List<String> visible() {
        return jobService.search(HistorySearchRequest.unfiltered()).getContent().stream()
                .map(JobHistoryDto::getInputFilename).sorted().toList();
    }

    private Long jobId(String file) {
        return jdbc.queryForObject("select id from job_records where input_filename = ?", Long.class, file);
    }

    // ── Whose runs are in the history ─────────────────────────────────────────

    @Test
    @DisplayName("a user sees their own runs and nobody else's")
    void aUserSeesTheirOwn() {
        as(danaId, "vis-dana");
        assertThat(visible()).containsExactly("dana.xlsx");
    }

    @Test
    @DisplayName("an administrator sees their own runs and every user's, but not another administrator's")
    void anAdminSeesTheirUsers() {
        as(bossId, "vis-boss");
        // Not peer.xlsx and not super.xlsx: managing accounts was never meant to mean reading the
        // other administrators' work.
        assertThat(visible()).containsExactly("boss.xlsx", "dana.xlsx", "nobody.xlsx", "sam.xlsx");
    }

    @Test
    @DisplayName("the superadmin sees everything")
    void theSuperadminSeesEverything() {
        as(superId, "admin");
        assertThat(visible())
                .containsExactly("boss.xlsx", "dana.xlsx", "nobody.xlsx", "peer.xlsx", "sam.xlsx", "super.xlsx");
    }

    @Test
    @DisplayName("runs from before there were accounts go to administrators, not to users")
    void ownerlessRunsGoUpwards() {
        // Switching authentication on mid-life leaves every earlier run ownerless. Handing them to
        // a new user would be handing them somebody else's history; hiding them from everybody
        // would quietly delete the past.
        as(danaId, "vis-dana");
        assertThat(visible()).doesNotContain("nobody.xlsx");

        SecurityContextHolder.clearContext();
        as(bossId, "vis-boss");
        assertThat(visible()).contains("nobody.xlsx");
    }

    @Test
    @DisplayName("the page count counts what may be seen, not what was hidden")
    void pagingCountsTheVisibleOnes() {
        as(danaId, "vis-dana");
        // Trimming after the query would page six rows and show one, leaving a history that claims
        // six runs and can display one of them.
        assertThat(jobService.getHistory(PageRequest.of(0, 20)).getTotalElements()).isEqualTo(1);
    }

    // ── The same rule, asked by id ────────────────────────────────────────────

    @Test
    @DisplayName("a run you cannot see is not found rather than forbidden")
    void hiddenRunsAreNotFound() {
        as(danaId, "vis-dana");
        Long id = jobId("sam.xlsx");

        assertThatThrownBy(() -> jobService.getById(id))
                .isInstanceOf(ApiException.class)
                // Told apart, the two answers would be a way to count other people's work.
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("the spreadsheet is guarded too, not only the row about it")
    void downloadFollowsTheSameRule() {
        as(danaId, "vis-dana");
        Long id = jobId("sam.xlsx");

        assertThatThrownBy(() -> jobService.downloadResult(id))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("a filter cannot ask about somebody else and get an answer")
    void filteringForAStrangerAnswersNothing() {
        as(danaId, "vis-dana");
        var askingAboutSam = new HistorySearchRequest(null, null, null, List.of(samId), null, null,
                null, null, null, null, null, null, null, null, null);

        assertThat(jobService.search(askingAboutSam).getContent()).isEmpty();
    }

    // ── The figures say the same as the list ──────────────────────────────────

    @Test
    @DisplayName("analytics counts the same runs the history shows")
    void analyticsAgreesWithTheHistory() {
        // The same information by a different door. Hiding the rows while publishing the totals
        // would make the history a decoration.
        as(danaId, "vis-dana");
        assertThat(analytics.summary(AnalyticsQuery.unfiltered()).totals().calls()).isEqualTo(1);

        SecurityContextHolder.clearContext();
        as(bossId, "vis-boss");
        assertThat(analytics.summary(AnalyticsQuery.unfiltered()).totals().calls()).isEqualTo(4);

        SecurityContextHolder.clearContext();
        as(superId, "admin");
        assertThat(analytics.summary(AnalyticsQuery.unfiltered()).totals().calls()).isEqualTo(6);
    }

    @Test
    @DisplayName("the per-person breakdown names only the people you may see")
    void theBreakdownStopsAtTheSamePeople() {
        as(bossId, "vis-boss");
        assertThat(analytics.summary(AnalyticsQuery.unfiltered()).byUser())
                .extracting(person -> person.name() == null ? "—" : person.name())
                .doesNotContain("vis-peer");
    }

    // ── Removing any of it ────────────────────────────────────────────────────

    @Test
    @DisplayName("an administrator cannot delete a run, not even one they can see")
    void deletingIsNotAdministration() {
        as(bossId, "vis-boss");
        Long id = jobId("dana.xlsx");

        assertThatThrownBy(() -> jobService.deleteJob(id)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a user cannot delete their own run either")
    void owningItIsNotEnough() {
        as(danaId, "vis-dana");
        Long id = jobId("dana.xlsx");

        // Deliberate, and the reason a deletion request is worth building: on an instance that
        // keeps an audit, being the person who made the mess is not authority to remove the record
        // of it.
        assertThatThrownBy(() -> jobService.deleteJob(id)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("the superadmin can")
    void theSuperadminCan() {
        as(superId, "admin");
        Long id = jobId("dana.xlsx");

        assertThatCode(() -> jobService.deleteJob(id)).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("select count(*) from job_records where id = ?", Integer.class, id))
                .isZero();
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
