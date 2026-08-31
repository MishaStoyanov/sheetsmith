package com.ap0stole.sheetsmith.schema;

import com.ap0stole.sheetsmith.auth.AuthenticatedUser;
import com.ap0stole.sheetsmith.configs.FileStorageConfig;
import com.ap0stole.sheetsmith.domain.dto.StorageSettingsDto;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.services.StorageQuotaService;
import com.ap0stole.sheetsmith.services.StorageSettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The storage cap: who may set it, and what it removes when it bites.
 * <p>
 * Who may set it is asked at the endpoint, in SecurityMatrixTest - the rule lives on the handler.
 * What is asked here is what the setting does once somebody is allowed to set it.
 * <p>
 * Written against real files in a temporary folder rather than a mocked filesystem, because the
 * whole feature is a claim about the disk. A test that agreed with a stub about how many bytes were
 * freed would prove nothing about the thing that actually deletes them.
 */
@SpringBootTest
@TestPropertySource(properties = "sheetsmith.auth.enabled=true")
@EnabledIf(value = "dockerAvailable", disabledReason = "Docker is not available")
class StorageLimitTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StorageSettingsService settings;

    @Autowired
    private StorageQuotaService quota;

    @Autowired
    private FileStorageConfig config;

    @TempDir
    Path root;

    /** Somewhere else to move the archive to, for the one test about what a move leaves behind. */
    @TempDir
    Path elsewhere;

    private Long superId;

    @BeforeEach
    void seed() {
        jdbc.update("delete from job_records");
        jdbc.update("delete from storage_settings");
        jdbc.update("delete from users where name like 'store-%'");

        superId = jdbc.queryForObject("select min(id) from users", Long.class);
        jdbc.update("update users set role = 'SUPERADMIN' where id = ?", superId);

        jdbc.update("insert into users (name, password_hash, must_change_password, role) "
                + "values ('store-dana', 'x', false, 'ADMIN')");
    }

    @AfterEach
    void tidy() {
        SecurityContextHolder.clearContext();
        jdbc.update("delete from job_records");
        jdbc.update("delete from storage_settings");
        jdbc.update("delete from users where name like 'store-%'");
    }

    private void as(Long id, String name) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthenticatedUser(id, name), null, List.of()));
    }

    /** Points storage at the temporary folder and sets the caps, as the superadmin would. */
    private void configure(Integer maxFiles, Long maxBytes) {
        as(superId, "admin");
        settings.update(new StorageSettingsDto.Update(root.toString(), maxFiles, maxBytes));
        SecurityContextHolder.clearContext();
    }

    /**
     * A finished run with real files of a given size, dated so the order of eviction is decidable.
     *
     * @return the run's id
     */
    private Long run(String name, String created, int bytes) throws IOException {
        return runIn(root, name, created, bytes);
    }

    private Long runIn(Path where, String name, String created, int bytes) throws IOException {
        Path input = where.resolve("uploads").resolve(name + "_in.xlsx");
        Path result = where.resolve("results").resolve(name + "_out.xlsx");
        Files.createDirectories(input.getParent());
        Files.createDirectories(result.getParent());
        Files.write(input, new byte[bytes]);
        Files.write(result, new byte[bytes]);

        jdbc.update("""
                insert into job_records (created_at, instruction, input_filename, input_file_path,
                        result_file_path, status, total_tokens, provider, model)
                values (?::timestamp, 'tidy it', ?, ?, ?, 'COMPLETED', 100, 'OPENAI', 'gpt-4o')
                """, created, name + ".xlsx", input.toString(), result.toString());
        return jdbc.queryForObject("select id from job_records where input_filename = ?",
                Long.class, name + ".xlsx");
    }

    private List<String> remaining() {
        return jdbc.queryForList("select input_filename from job_records order by created_at", String.class);
    }

    // ── Who may say ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("a folder that cannot be written to is refused when it is chosen, not when a file arrives")
    void anUnusableFolderIsRefusedUpFront() {
        as(superId, "admin");
        // A path that cannot be a directory at all — the point is that the refusal happens here,
        // where somebody is looking at the message, rather than during a run hours later.
        Path notAFolder = root.resolve("uploads").resolve("blocked");
        assertThatCode(() -> Files.createDirectories(notAFolder.getParent())).doesNotThrowAnyException();
        assertThatCode(() -> Files.write(notAFolder, new byte[]{1})).doesNotThrowAnyException();

        var unusable = new StorageSettingsDto.Update(notAFolder.toString(), null, null);
        assertThatThrownBy(() -> settings.update(unusable))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Cannot use");
    }

    @Test
    @DisplayName("a cap of zero is refused rather than taken literally")
    void zeroIsNotAnAnswer() {
        as(superId, "admin");
        // It would mean "delete every run as it finishes", which nobody says by leaving a box at 0.
        var keepNothing = new StorageSettingsDto.Update(null, 0, null);
        assertThatThrownBy(() -> settings.update(keepNothing))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("every run");
    }

    @Test
    @DisplayName("no folder of its own means the directories the instance was started with")
    void unsetMeansTheConfiguredDirectories() {
        as(superId, "admin");
        StorageSettingsDto dto = settings.get();

        assertThat(dto.rootDir()).isNull();
        assertThat(dto.uploadDir())
                .isEqualTo(Path.of(config.getUploadDir()).toAbsolutePath().normalize().toString());
    }

    // ── What it removes ───────────────────────────────────────────────────────

    @Test
    @DisplayName("over the file count, the oldest run goes first")
    void oldestFirstByCount() throws IOException {
        // Two files per run — an input and a result — so four runs is eight spreadsheets.
        configure(6, null);
        run("first", "2026-08-01 10:00", 10);
        run("second", "2026-08-02 10:00", 10);
        run("third", "2026-08-03 10:00", 10);
        run("fourth", "2026-08-04 10:00", 10);

        quota.enforce();

        assertThat(remaining()).containsExactly("second.xlsx", "third.xlsx", "fourth.xlsx");
    }

    @Test
    @DisplayName("the files actually leave the disk, not just the table")
    void evictionDeletesTheFiles() throws IOException {
        configure(2, null);
        run("first", "2026-08-01 10:00", 10);
        run("second", "2026-08-02 10:00", 10);

        quota.enforce();

        assertThat(Files.exists(root.resolve("uploads").resolve("first_in.xlsx"))).isFalse();
        assertThat(Files.exists(root.resolve("results").resolve("first_out.xlsx"))).isFalse();
        assertThat(Files.exists(root.resolve("uploads").resolve("second_in.xlsx"))).isTrue();
    }

    @Test
    @DisplayName("a size cap counts bytes, not files")
    void oldestFirstByBytes() throws IOException {
        // One big run and one small one: by count both fit, by size only the newer does.
        configure(null, 1_000L);
        run("big", "2026-08-01 10:00", 900);
        run("small", "2026-08-02 10:00", 100);

        quota.enforce();

        assertThat(remaining()).containsExactly("small.xlsx");
    }

    @Test
    @DisplayName("both caps hold at once, whichever bites first")
    void bothCapsApply() throws IOException {
        // Four spreadsheets is inside the count; their bytes are not.
        configure(10, 500L);
        run("first", "2026-08-01 10:00", 400);
        run("second", "2026-08-02 10:00", 100);

        quota.enforce();

        assertThat(remaining()).containsExactly("second.xlsx");
    }

    @Test
    @DisplayName("after the archive moves, the cap still empties down to what it can see")
    void aMoveDoesNotStopThePassEarly() throws IOException {
        // One run written before the move — its files stay where they were, as the setting promises.
        configure(null, null);
        run("before", "2026-08-01 10:00", 10);

        // Then the archive moves and two more runs are written to the new place. Four spreadsheets
        // there against a cap of two.
        as(superId, "admin");
        settings.update(new StorageSettingsDto.Update(elsewhere.toString(), 2, null));
        SecurityContextHolder.clearContext();
        runIn(elsewhere, "after-one", "2026-08-02 10:00", 10);
        runIn(elsewhere, "after-two", "2026-08-03 10:00", 10);

        quota.enforce();

        // The old run's files are still disk and still go — but they were never part of the figure
        // being measured, so counting them as freed would have stopped the pass one run early and
        // left the new folder over its cap while reporting it under.
        assertThat(remaining()).containsExactly("after-two.xlsx");
    }

    @Test
    @DisplayName("with no cap set nothing is ever removed")
    void noCapMeansKeepEverything() throws IOException {
        configure(null, null);
        run("first", "2026-08-01 10:00", 10_000);
        run("second", "2026-08-02 10:00", 10_000);

        assertThat(quota.enforce().runs()).isZero();
        assertThat(remaining()).containsExactly("first.xlsx", "second.xlsx");
    }

    @Test
    @DisplayName("a run still processing is never evicted, however old it is")
    void liveRunsAreNotCandidates() throws IOException {
        configure(2, null);
        Long running = run("running", "2026-08-01 10:00", 10);
        jdbc.update("update job_records set status = 'PROCESSING' where id = ?", running);
        run("finished", "2026-08-02 10:00", 10);

        quota.enforce();

        // Its input is open on another thread: taking it away fails the run rather than freeing the
        // disk. What is left is over the cap, which is said in the log rather than forced.
        assertThat(remaining()).contains("running.xlsx");
    }

    @Test
    @DisplayName("a session's revisions are not evictable, and the pass moves on rather than spinning")
    void sessionFilesSurvive() throws IOException {
        configure(1, null);

        // A run whose files are the session's own revision chain — deleting them would punch a hole
        // in somebody's undo history or take the sheet they have open.
        Path revision = Path.of(config.getSessionDir()).resolve("s-live").resolve("rev-0.xlsx");
        Files.createDirectories(revision.getParent());
        Files.write(revision, new byte[10]);
        jdbc.update("""
                insert into job_records (created_at, instruction, input_filename, input_file_path,
                        status, total_tokens, provider, model)
                values ('2026-08-01 10:00'::timestamp, 'tidy it', 'live.xlsx', ?, 'COMPLETED', 10, 'OPENAI', 'gpt-4o')
                """, revision.toString());
        run("archived", "2026-08-02 10:00", 10);

        assertThatCode(() -> quota.enforce()).doesNotThrowAnyException();

        assertThat(Files.exists(revision))
                .as("a live session's revision is not the storage cap's to take")
                .isTrue();
        assertThat(remaining())
                .as("nor is its row: deleting it would cost a line of history and free nothing")
                .contains("live.xlsx");
        Files.deleteIfExists(revision);
    }

    @Test
    @DisplayName("what is on disk is what is counted")
    void usageIsMeasuredFromTheDisk() throws IOException {
        configure(null, null);
        run("first", "2026-08-01 10:00", 500);

        as(superId, "admin");
        StorageSettingsDto dto = settings.get();

        assertThat(dto.fileCount()).isEqualTo(2);
        assertThat(dto.bytesUsed()).isEqualTo(1_000);
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
