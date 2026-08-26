package com.ap0stole.sheetsmith;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Guards application startup: every bean, {@code @ConfigurationProperties} class and registry must wire
 * against a throwaway PostgreSQL. Needs Docker; skips instead of failing when Docker is unavailable.
 */
@SpringBootTest
@EnabledIf(value = "dockerAvailable", disabledReason = "Docker is not available")
class SheetSmithApplicationTests {

    @Test
    void contextLoads() {
    }

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The container is built inside a bean method, not a static field, so nothing touches Docker until the
     * condition above has passed and the context actually starts.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresContainerConfiguration {

        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgresContainer() {
            return new PostgreSQLContainer<>("postgres:16-alpine");
        }
    }

}
