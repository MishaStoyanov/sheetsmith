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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the seeded first user is real and that its password is the one the README will tell people.
 * <p>
 * The hash is a constant in a migration, which means a typo in it is invisible: the app starts, the
 * row exists, and the only symptom is that nobody can ever log in. So the test does not check that
 * a hash is present — it checks that the hash matches the word {@code admin}.
 */
@SpringBootTest
@EnabledIf(value = "dockerAvailable", disabledReason = "Docker is not available")
class AdminSeedTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("the seeded admin's password really is 'admin'")
    void theSeededPasswordIsTheDocumentedOne() {
        String hash = jdbc.queryForObject(
                "select password_hash from users where name = 'admin'", String.class);

        assertThat(new BCryptPasswordEncoder().matches("admin", hash))
                .as("a mistyped constant would leave an instance nobody can ever sign in to")
                .isTrue();
    }

    @Test
    @DisplayName("the seeded admin is flagged to change it, and only the seeded admin is")
    void onlyTheSeededAdminIsFlagged() {
        Boolean flagged = jdbc.queryForObject(
                "select must_change_password from users where name = 'admin'", Boolean.class);
        assertThat(flagged).isTrue();

        jdbc.update("insert into users (name, password_hash) values ('seed-test-user', 'x')");
        Boolean other = jdbc.queryForObject(
                "select must_change_password from users where name = 'seed-test-user'", Boolean.class);

        assertThat(other)
                .as("the column defaults to false: a nag meant for the seeded row must not follow "
                        + "every user anyone creates")
                .isFalse();

        jdbc.update("delete from users where name = 'seed-test-user'");
    }

    @Test
    @DisplayName("exactly one admin, so re-running the migrations cannot make a second")
    void theSeedIsNotDuplicated() {
        Integer admins = jdbc.queryForObject(
                "select count(*) from users where name = 'admin'", Integer.class);

        assertThat(admins).isEqualTo(1);
    }

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {

        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:16-alpine");
        }
    }
}
