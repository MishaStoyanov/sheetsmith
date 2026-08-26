package com.ap0stole.sheetsmith.configs;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * The app ships without authentication, so these two settings are the only thing between a
 * self-hosted instance and whatever else the operator has running on the machine. Both default
 * to the closed position; opening them is a deliberate act.
 */
@Slf4j
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "xlsxai.security")
public class SecurityConfig {

    /** Browser origins allowed to call /api/**. A wildcard would let any open tab drive the instance. */
    private List<String> allowedOrigins = new ArrayList<>(List.of(
            "http://localhost:5173",
            "http://127.0.0.1:5173",
            "http://localhost:8080",
            "http://127.0.0.1:8080"));

    /** POST /api/excel/improve/path reads and writes caller-supplied paths — off unless asked for. */
    private boolean pathEndpointEnabled = false;

    /** Directories that endpoint may read from and write to. Nothing outside them is reachable. */
    private List<String> pathEndpointRoots = new ArrayList<>();

    @PostConstruct
    public void validate() {
        if (allowedOrigins.isEmpty()) {
            log.warn("xlsxai.security.allowed-origins is empty: every browser origin will be refused");
        }
        if (!pathEndpointEnabled) {
            return;
        }
        if (pathEndpointRoots.stream().allMatch(root -> root == null || root.isBlank())) {
            throw new IllegalStateException(
                    "XLSXAI_PATH_ENDPOINT_ENABLED is true but XLSXAI_PATH_ENDPOINT_ROOTS is empty. "
                            + "Refusing to start: that combination would expose the whole filesystem "
                            + "through POST /api/excel/improve/path.");
        }
        log.warn("By-path endpoint ENABLED: POST /api/excel/improve/path may read and write inside {}",
                pathEndpointRoots);
    }
}
