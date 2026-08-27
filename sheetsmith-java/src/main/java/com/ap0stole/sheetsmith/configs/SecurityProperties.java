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
 * Settings for the two guards that stand in front of an instance regardless of whether anyone has
 * to log in: which browser origins may call the API, and whether the by-path endpoint exists at
 * all. Both default to the closed position; opening them is a deliberate act.
 * <p>
 * Named {@code Properties} rather than {@code Config} because {@link SecurityConfig} is now the
 * filter chain, which is what a Spring developer opens that file expecting to find. (Spring Boot
 * has a {@code SecurityProperties} of its own under a different prefix; the two never meet.)
 */
@Slf4j
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "sheetsmith.security")
public class SecurityProperties {

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
            log.warn("sheetsmith.security.allowed-origins is empty: every browser origin will be refused");
        }
        if (!pathEndpointEnabled) {
            return;
        }
        if (pathEndpointRoots.stream().allMatch(root -> root == null || root.isBlank())) {
            throw new IllegalStateException(
                    "SHEETSMITH_PATH_ENDPOINT_ENABLED is true but SHEETSMITH_PATH_ENDPOINT_ROOTS is empty. "
                            + "Refusing to start: that combination would expose the whole filesystem "
                            + "through POST /api/excel/improve/path.");
        }
        log.warn("By-path endpoint ENABLED: POST /api/excel/improve/path may read and write inside {}",
                pathEndpointRoots);
    }
}
