package com.ap0stole.sheetsmith.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That an empty database really is built by the migrations, and that the mappings agree with what
 * they built.
 * <p>
 * The agreement half is not asserted here because it cannot be: {@code ddl-auto: validate} runs
 * during startup, so a column the migrations forgot fails this test by never letting the context
 * start — which is the whole point of leaving {@code update} behind.
 */
@SpringBootTest
@EnabledIf(value = "dockerAvailable", disabledReason = "Docker is not available")
class SchemaMigrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("baseline builds every table the app maps")
    void baselineCreatesSchema() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public' order by table_name",
                String.class);

        assertThat(tables).contains(
                "action_results", "chat_messages", "chat_sessions", "chat_steps", "job_records", "llm_settings");
    }

    @Test
    @DisplayName("the baseline is recorded as applied, not merely assumed")
    void baselineIsRecorded() {
        List<String> applied = jdbc.queryForList(
                "select version from flyway_schema_history where success order by installed_rank", String.class);

        assertThat(applied).startsWith("1");
    }

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresContainerConfiguration {

        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgresContainer() {
            return new PostgreSQLContainer<>("postgres:16-alpine");
        }
    }
}
