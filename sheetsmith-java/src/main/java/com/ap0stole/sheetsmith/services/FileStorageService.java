package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.configs.FileStorageConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final FileStorageConfig config;

    public String saveInput(MultipartFile file) throws IOException {
        String sanitized = sanitize(file.getOriginalFilename());
        Path target = Path.of(config.getUploadDir()).resolve(sanitized + "_" + UUID.randomUUID() + ".xlsx");
        Files.copy(file.getInputStream(), target);
        log.info("Saved input file: {}", target);
        return target.toAbsolutePath().toString();
    }

    public String buildResultPath(String inputFilePath) {
        String filename = Path.of(inputFilePath).getFileName().toString();
        return Path.of(config.getResultDir()).resolve(filename).toAbsolutePath().toString();
    }

    /**
     * Deletes the files of a job that is going away — except the ones a session owns. A job run
     * against a session reads and writes revisions of that session's chain, and deleting those would
     * punch a hole in the undo history or destroy the sheet the user is currently looking at. Those
     * files outlive the job record and go when the session goes.
     */
    public void deleteJobFiles(String inputPath, String resultPath) {
        deleteUnlessSessionOwned(inputPath);
        deleteUnlessSessionOwned(resultPath);
    }

    /** True when the path lives under the session storage root, i.e. it is a session revision file. */
    public boolean isSessionOwned(String filePath) {
        if (filePath == null) return false;
        try {
            Path root = Path.of(config.getSessionDir()).toAbsolutePath().normalize();
            return Path.of(filePath).toAbsolutePath().normalize().startsWith(root);
        } catch (Exception e) {
            log.warn("Could not classify path {}: {}", filePath, e.getMessage());
            return false;
        }
    }

    private void deleteUnlessSessionOwned(String filePath) {
        if (filePath == null) return;
        if (isSessionOwned(filePath)) {
            log.debug("Keeping {} — it belongs to a chat session's revision chain", filePath);
            return;
        }
        try {
            if (Files.deleteIfExists(Path.of(filePath))) {
                log.debug("Deleted file {}", filePath);
            }
        } catch (Exception e) {
            log.warn("Could not delete file {}: {}", filePath, e.getMessage());
        }
    }

    private String sanitize(String filename) {
        if (filename == null) return "file";
        String base = filename.contains(".") ? filename.substring(0, filename.lastIndexOf('.')) : filename;
        return base.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
