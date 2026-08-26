package com.ap0stole.sheetsmith.configs;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "sheetsmith.storage")
public class FileStorageConfig {

    private String uploadDir;
    private String resultDir;
    /** Per-chat-session working copies, one sub-directory per session holding its revisions. */
    private String sessionDir;
    private int ttlDays;

    @PostConstruct
    public void createDirectories() throws IOException {
        Files.createDirectories(Path.of(uploadDir));
        Files.createDirectories(Path.of(resultDir));
        Files.createDirectories(Path.of(sessionDir));
        log.info("Storage directories ready: uploads='{}', results='{}', sessions='{}'",
                uploadDir, resultDir, sessionDir);
    }
}
