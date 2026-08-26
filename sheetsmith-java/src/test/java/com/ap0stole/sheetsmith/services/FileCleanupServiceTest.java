package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.configs.FileStorageConfig;
import com.ap0stole.sheetsmith.domain.entity.JobRecord;
import com.ap0stole.sheetsmith.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Expiring a job must not expire a sheet: since improve runs write into a session's revision chain,
 * an old job record can point at files a live session is still using.
 */
class FileCleanupServiceTest {

    private Path uploadDir;
    private Path sessionDir;
    private JobRepository jobRepository;
    private FileCleanupService cleanupService;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        uploadDir = Files.createDirectories(tempDir.resolve("uploads"));
        sessionDir = Files.createDirectories(tempDir.resolve("sessions").resolve("session-1"));

        FileStorageConfig storageConfig = new FileStorageConfig();
        storageConfig.setUploadDir(uploadDir.toString());
        storageConfig.setResultDir(Files.createDirectories(tempDir.resolve("results")).toString());
        storageConfig.setSessionDir(tempDir.resolve("sessions").toString());
        storageConfig.setTtlDays(7);

        jobRepository = mock(JobRepository.class);
        cleanupService = new FileCleanupService(jobRepository, storageConfig,
                new FileStorageService(storageConfig), mock(DocumentSessionService.class));
    }

    @Test
    @DisplayName("an expired session-backed job drops its record but leaves the session's revisions")
    void keepsSessionRevisions() throws Exception {
        Path revision0 = Files.createFile(sessionDir.resolve("rev-0.xlsx"));
        Path revision1 = Files.createFile(sessionDir.resolve("rev-1.xlsx"));
        JobRecord job = JobRecord.create("sort it", "sales.xlsx", revision0.toString());
        job.setResultFilePath(revision1.toString());
        when(jobRepository.findByCreatedAtBefore(any(LocalDateTime.class))).thenReturn(List.of(job));

        cleanupService.cleanupExpiredJobs();

        assertThat(revision0).exists();
        assertThat(revision1).exists();
        verify(jobRepository).deleteAll(List.of(job));
    }

    @Test
    @DisplayName("an expired file-passing job still takes its upload and result with it")
    void deletesStandaloneFiles() throws Exception {
        Path input = Files.createFile(uploadDir.resolve("sales.xlsx"));
        JobRecord job = JobRecord.create("sort it", "sales.xlsx", input.toString());
        when(jobRepository.findByCreatedAtBefore(any(LocalDateTime.class))).thenReturn(List.of(job));

        cleanupService.cleanupExpiredJobs();

        assertThat(input).doesNotExist();
        verify(jobRepository).deleteAll(List.of(job));
    }
}
