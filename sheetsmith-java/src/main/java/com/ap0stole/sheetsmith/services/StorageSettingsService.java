package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.configs.FileStorageConfig;
import com.ap0stole.sheetsmith.domain.dto.StorageSettingsDto;
import com.ap0stole.sheetsmith.domain.entity.StorageSettingsEntity;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import com.ap0stole.sheetsmith.repository.StorageSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Where this instance keeps its spreadsheets, and how much of them it keeps.
 * <p>
 * <strong>Why it is a setting at all.</strong> The history offers a Download button, which means the
 * server is holding on to every file it has ever been given. Somebody has to be able to say where
 * that lives and when it stops growing, and telling them to restart the process with a different
 * environment variable is not saying it.
 * <p>
 * <strong>Why only the superadmin.</strong> A folder is a path on the server's filesystem, and
 * choosing it is closer to configuring the machine than to administering people. The caps are the
 * same act from the other side: setting one small enough is a way to delete other people's work
 * without ever pressing Delete.
 * <p>
 * <strong>Changing the folder moves nothing.</strong> Files already written keep being served from
 * the path recorded against their run — moving them would be a long, interruptible copy that can
 * half-finish, and a half-finished one leaves a history full of rows whose files are somewhere
 * else. New files go to the new place; the old ones stay readable where they are and age out on
 * their own.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageSettingsService {

    /** The two folders under a chosen root, named here because three places have to agree on them. */
    private static final String UPLOADS = "uploads";
    private static final String RESULTS = "results";

    private final StorageSettingsRepository repository;
    private final FileStorageConfig config;

    /** The extension that counts as a spreadsheet here, for both the count and the size. */
    private static final String SPREADSHEET = ".xlsx";

    // ── What the rest of the application asks ─────────────────────────────────

    /**
     * Where inputs are written now.
     * <p>
     * Absolute and normalised, because this path is shown to a person as well as used: the
     * configured default is {@code ./uploads}, and left alone it reached the settings screen with a
     * bare {@code .} left in the middle of it — a path that works and reads like a mistake.
     */
    public Path uploadDir() {
        return chosenRoot().map(root -> root.resolve(UPLOADS))
                .orElseGet(() -> Path.of(config.getUploadDir()))
                .toAbsolutePath().normalize();
    }

    /** Where results are written now. Absolute and normalised for the same reason. */
    public Path resultDir() {
        return chosenRoot().map(root -> root.resolve(RESULTS))
                .orElseGet(() -> Path.of(config.getResultDir()))
                .toAbsolutePath().normalize();
    }

    /** How many spreadsheets to keep, if anybody said. */
    public Optional<Integer> maxFiles() {
        return row().map(StorageSettingsEntity::getMaxFiles);
    }

    /** How much disk to use, if anybody said. */
    public Optional<Long> maxBytes() {
        return row().map(StorageSettingsEntity::getMaxBytes);
    }

    // ── What the screen asks ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public StorageSettingsDto get() {
        return describe(row().orElse(null));
    }

    /**
     * Saves the choice, having first proved the folder can be used.
     * <p>
     * The proof is a file actually written and removed rather than a permissions check: on Windows
     * a directory can be listable, look writable, and still refuse every write, and the only
     * question worth answering here is the one the application will ask later.
     */
    @Transactional
    public StorageSettingsDto update(StorageSettingsDto.Update update) {
        String root = update.rootDir() == null || update.rootDir().isBlank() ? null : update.rootDir().trim();
        if (root != null) {
            prepare(Path.of(root));
        }
        if (update.maxFiles() != null && update.maxFiles() < 1) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "A limit of zero spreadsheets would delete every run as it finished. "
                            + "Leave it empty for no limit.", "maxFiles");
        }
        if (update.maxBytes() != null && update.maxBytes() < 1) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "A limit of zero would delete every run as it finished. Leave it empty for no limit.",
                    "maxBytes");
        }

        StorageSettingsEntity entity = repository.findById(StorageSettingsEntity.GLOBAL_ID)
                .orElseGet(StorageSettingsEntity::new);
        entity.setId(StorageSettingsEntity.GLOBAL_ID);
        entity.setRootDir(root);
        entity.setMaxFiles(update.maxFiles());
        entity.setMaxBytes(update.maxBytes());
        entity.setUpdatedAt(LocalDateTime.now());
        repository.save(entity);

        log.info("Storage settings updated: root={}, maxFiles={}, maxBytes={}",
                root == null ? "(as started)" : root, update.maxFiles(), update.maxBytes());
        return describe(entity);
    }

    // ── Measuring what is there ───────────────────────────────────────────────

    /**
     * The archive as it stands: the input and result files, counted off the disk.
     * <p>
     * Measured rather than derived from the run records, because the number that matters is the one
     * the filesystem would report. A file left behind by a crash still takes the space up, and a
     * cap that could not see it would be a cap on the bookkeeping rather than on the disk.
     * <p>
     * Session revisions are not counted. They are somebody's open document rather than an archive,
     * they age out on the idle timer, and evicting them would take a sheet out from under the
     * person editing it.
     */
    public Usage usage() {
        long[] totals = new long[2];
        for (Path dir : new Path[]{uploadDir(), resultDir()}) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().toLowerCase().endsWith(SPREADSHEET))
                        .forEach(file -> {
                            totals[0]++;
                            try {
                                totals[1] += Files.size(file);
                            } catch (IOException e) {
                                log.debug("Could not size {}: {}", file, e.getMessage());
                            }
                        });
            } catch (IOException e) {
                log.warn("Could not read storage directory {}: {}", dir, e.getMessage());
            }
        }
        return new Usage((int) totals[0], totals[1]);
    }

    /** What the archive holds right now. */
    public record Usage(int files, long bytes) {
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private Optional<StorageSettingsEntity> row() {
        return repository.findById(StorageSettingsEntity.GLOBAL_ID);
    }

    private Optional<Path> chosenRoot() {
        return row().map(StorageSettingsEntity::getRootDir)
                .filter(dir -> !dir.isBlank())
                .map(Path::of);
    }

    /** Creates the folder and its two children, and proves a file can be written into them. */
    private void prepare(Path root) {
        try {
            Files.createDirectories(root.resolve(UPLOADS));
            Files.createDirectories(root.resolve(RESULTS));
            Path probe = Files.createTempFile(root.resolve(UPLOADS), "sheetsmith-", ".probe");
            Files.deleteIfExists(probe);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Cannot use " + root + " — " + e.getMessage()
                            + ". The server has to be able to create files there itself.", "rootDir");
        }
    }

    private StorageSettingsDto describe(StorageSettingsEntity entity) {
        Usage usage = usage();
        Path uploads = uploadDir();
        return new StorageSettingsDto(
                entity == null ? null : entity.getRootDir(),
                entity == null ? null : entity.getMaxFiles(),
                entity == null ? null : entity.getMaxBytes(),
                uploads.toAbsolutePath().toString(),
                resultDir().toAbsolutePath().toString(),
                usage.files(),
                usage.bytes(),
                Files.isWritable(uploads));
    }
}
