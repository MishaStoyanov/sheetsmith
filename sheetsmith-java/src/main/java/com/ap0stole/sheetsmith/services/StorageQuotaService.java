package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.domain.entity.JobRecord;
import com.ap0stole.sheetsmith.domain.enums.JobStatus;
import com.ap0stole.sheetsmith.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Keeps the archive inside whatever the superadmin said it could be.
 * <p>
 * <strong>Oldest first, and only finished runs.</strong> The rule has to be one somebody can predict
 * without reading this class: the next file to go is the one that has been there longest. A run
 * still processing is never a candidate, however old it looks — its input is open on another thread
 * and taking it away would fail the run rather than free the disk.
 * <p>
 * <strong>Both caps, whichever bites first.</strong> A count and a size answer different worries —
 * "how much history do I want" and "how much disk do I have" — and an instance that set both meant
 * both.
 * <p>
 * <strong>What it never touches.</strong> Session revision files go through
 * {@link FileStorageService#deleteJobFiles}, which refuses them: they are the undo history of a
 * document somebody has open, and freeing disk by deleting the sheet under an editor is not freeing
 * disk. A run whose files are all session-owned therefore frees nothing, and is left alone
 * entirely — row included: removing it would cost somebody a line of history and free not one
 * byte.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageQuotaService {

    private final JobRepository jobs;
    private final FileStorageService files;
    private final StorageSettingsService settings;

    /** How many runs one pass will consider, so a misconfigured cap cannot empty the table in a loop. */
    private static final int BATCH = 200;

    /**
     * Removes the oldest runs until the archive fits, and reports what went.
     * <p>
     * Called after a run finishes and again by the nightly cleanup. Doing it on the way in — before
     * accepting the upload — would refuse work to make room for it, which is the wrong way round:
     * the person asking for this run is not the person whose old run should have gone.
     */
    @Transactional
    public Evicted enforce() {
        Integer maxFiles = settings.maxFiles().orElse(null);
        Long maxBytes = settings.maxBytes().orElse(null);
        if (maxFiles == null && maxBytes == null) {
            return Evicted.NOTHING;
        }

        StorageSettingsService.Usage usage = settings.usage();
        if (fits(usage, maxFiles, maxBytes)) {
            return Evicted.NOTHING;
        }

        // Finished runs only, oldest first. Sorted by the database rather than in Java: the batch
        // has to be the oldest two hundred, not two hundred arbitrary rows sorted afterwards.
        List<JobRecord> candidates = jobs.findByStatusInOrderByCreatedAtAsc(
                List.of(JobStatus.COMPLETED, JobStatus.PARTIAL, JobStatus.FAILED),
                PageRequest.of(0, BATCH));

        int removed = 0;
        long freed = 0;
        for (JobRecord job : candidates) {
            if (fits(usage, maxFiles, maxBytes)) {
                break;
            }
            // Measured before the files go, because afterwards there is nothing left to measure.
            // Two stat calls per eviction, rather than walking both directories again after each
            // one — and the difference is what actually left the disk, so a run whose files were a
            // session's frees nothing and is counted as freeing nothing.
            if (!hasDeletableFiles(job)) {
                // Nothing here to delete: the files are a live session's, or they are already gone.
                // Removing the row anyway would cost somebody a line of history and free not one
                // byte — and, worse, leave a session file on disk that no record accounts for.
                log.debug("Storage: run {} holds nothing the cap can free, leaving it alone", job.getId());
                continue;
            }
            int countBefore = countOf(job);
            long sizeBefore = sizeOf(job);

            files.deleteJobFiles(job.getInputFilePath(), job.getResultFilePath());
            jobs.delete(job);
            removed++;

            int stillThere = countOf(job);
            long stillTaking = sizeOf(job);
            freed += sizeBefore - stillTaking;
            usage = new StorageSettingsService.Usage(
                    usage.files() - (countBefore - stillThere), usage.bytes() - (sizeBefore - stillTaking));
        }

        if (removed > 0) {
            log.info("Storage: evicted {} run(s), freeing {} bytes (limits: files={}, bytes={})",
                    removed, freed, maxFiles, maxBytes);
        }
        if (!fits(usage, maxFiles, maxBytes)) {
            // Said out loud rather than retried. Either everything left is a live session's, or
            // there are files on disk that no run accounts for — both need a person, not a loop.
            log.warn("Storage is still over its limit after evicting {} run(s): {} files, {} bytes. "
                            + "What is left is either in use or not accounted for by any run.",
                    removed, usage.files(), usage.bytes());
        }
        return new Evicted(removed, freed);
    }

    /** How many runs were removed to make room, and how much that freed. */
    public record Evicted(int runs, long bytes) {

        static final Evicted NOTHING = new Evicted(0, 0);
    }

    private boolean fits(StorageSettingsService.Usage usage, Integer maxFiles, Long maxBytes) {
        return (maxFiles == null || usage.files() <= maxFiles)
                && (maxBytes == null || usage.bytes() <= maxBytes);
    }

    /** Whether there is a file here the cap may actually delete, wherever it happens to live. */
    private boolean hasDeletableFiles(JobRecord job) {
        return deletable(job.getInputFilePath()) || deletable(job.getResultFilePath());
    }

    private boolean deletable(String path) {
        return path != null && !files.isSessionOwned(path) && Files.exists(Path.of(path));
    }

    private long sizeOf(JobRecord job) {
        return size(job.getInputFilePath()) + size(job.getResultFilePath());
    }

    private int countOf(JobRecord job) {
        return (counted(job.getInputFilePath()) ? 1 : 0) + (counted(job.getResultFilePath()) ? 1 : 0);
    }

    /**
     * Whether this file is one of the ones the running total was measured over.
     * <p>
     * The total comes from listing the directories in use, so a file the folder move left behind is
     * not in it. Subtracting such a file after deleting it would walk the total down past what is
     * really there and stop the pass early, leaving the archive over its cap and saying it was not.
     * Deleting it is still right — it is disk, and the run is going — it just is not a subtraction.
     */
    private boolean counted(String path) {
        if (path == null || files.isSessionOwned(path)) {
            return false;
        }
        try {
            Path file = Path.of(path).toAbsolutePath().normalize();
            if (!Files.exists(file)) {
                return false;
            }
            Path parent = file.getParent();
            return parent != null && (parent.equals(settings.uploadDir().toAbsolutePath().normalize())
                    || parent.equals(settings.resultDir().toAbsolutePath().normalize()));
        } catch (Exception _) {
            return false;
        }
    }

    private long size(String path) {
        if (!counted(path)) {
            return 0;
        }
        try {
            return Files.size(Path.of(path));
        } catch (Exception _) {
            return 0;
        }
    }
}
