package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.configs.FileStorageConfig;
import com.ap0stole.sheetsmith.domain.entity.JobRecord;
import com.ap0stole.sheetsmith.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileCleanupService {

    private final JobRepository jobRepository;
    private final FileStorageConfig storageConfig;
    private final FileStorageService fileStorageService;
    private final DocumentSessionService documentSessionService;
    private final StorageQuotaService storageQuota;

    /** Where "now" comes from, so a test can decide what it is. */
    private final Clock clock;

    @Scheduled(cron = "0 0 2 * * *")
    public void cleanupIdleDocumentSessions() {
        LocalDateTime threshold = LocalDateTime.now(clock).minusDays(storageConfig.getTtlDays());
        int deleted = documentSessionService.deleteIdleSince(threshold);
        if (deleted > 0) {
            log.info("Cleanup: deleted {} idle chat sessions (older than {} days)", deleted, storageConfig.getTtlDays());
        }
    }

    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void cleanupExpiredJobs() {
        LocalDateTime threshold = LocalDateTime.now(clock).minusDays(storageConfig.getTtlDays());
        List<JobRecord> expired = jobRepository.findByCreatedAtBefore(threshold);

        if (expired.isEmpty()) {
            log.debug("Cleanup: no expired jobs found (threshold={})", threshold);
            return;
        }

        log.info("Cleanup: deleting {} expired jobs (older than {} days)", expired.size(), storageConfig.getTtlDays());
        // Revision files of a session are skipped: sessions age out on their own schedule above, and
        // an expired job must not take the sheet a still-live session is working on with it.
        for (JobRecord job : expired) {
            fileStorageService.deleteJobFiles(job.getInputFilePath(), job.getResultFilePath());
        }
        jobRepository.deleteAll(expired);
        log.info("Cleanup: deleted {} jobs", expired.size());
    }

    /**
     * The storage cap, checked again nightly.
     * <p>
     * It is enforced as each run finishes, so this pass normally finds nothing. It exists for the
     * cases the other one cannot see: a cap lowered while the instance was idle, and files that
     * grew or arrived without a run to trigger the check.
     */
    @Scheduled(cron = "0 30 2 * * *")
    public void enforceStorageLimits() {
        storageQuota.enforce();
    }
}
