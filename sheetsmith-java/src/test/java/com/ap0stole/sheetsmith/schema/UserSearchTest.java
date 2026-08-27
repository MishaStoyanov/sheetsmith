package com.ap0stole.sheetsmith.schema;

import com.ap0stole.sheetsmith.domain.dto.user.UserDto;
import com.ap0stole.sheetsmith.domain.dto.user.UserSearchRequest;
import com.ap0stole.sheetsmith.services.UserService;
import org.junit.jupiter.api.BeforeEach;
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
 * The user search, executed rather than mocked.
 * <p>
 * This exists because of a bug the mocked service test could not have caught: the query guarded an
 * optional keyword with {@code :keyword is null or ...}, and a null string parameter reaches
 * PostgreSQL untyped, where the driver infers {@code bytea} and {@code lower(bytea)} does not
 * exist. Every unit test passed and the screen answered 500 the first time it was opened. A test
 * that stubs the repository asserts the stub.
 */
@SpringBootTest
@EnabledIf(value = "dockerAvailable", disabledReason = "Docker is not available")
class UserSearchTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserService userService;

    @BeforeEach
    void seed() {
        jdbc.update("delete from users where name in ('Dana', 'daniel', 'erin')");
        jdbc.update("insert into users (name, password_hash) values ('Dana', 'x'), ('daniel', 'x'), ('erin', 'x')");
    }

    private List<String> names(UserSearchRequest request) {
        return userService.search(request).getContent().stream().map(UserDto::name).toList();
    }

    @Test
    @DisplayName("no keyword lists everyone")
    void noKeywordListsEveryone() {
        assertThat(names(new UserSearchRequest(null, 0, 50, "name", "asc")))
                .contains("Dana", "daniel", "erin");
    }

    @Test
    @DisplayName("a blank keyword is the same as none")
    void blankIsTheSameAsNone() {
        assertThat(names(new UserSearchRequest("   ", 0, 50, "name", "asc")))
                .contains("Dana", "daniel", "erin");
    }

    @Test
    @DisplayName("matching ignores case on both sides")
    void matchingIgnoresCase() {
        assertThat(names(new UserSearchRequest("DAN", 0, 50, "name", "asc")))
                .containsExactlyInAnyOrder("Dana", "daniel");
    }

    @Test
    @DisplayName("a fragment matches inside the name, not only at the start")
    void matchesInsideTheName() {
        assertThat(names(new UserSearchRequest("ani", 0, 50, "name", "asc")))
                .containsExactly("daniel");
    }

    @Test
    @DisplayName("the default account is flagged so the screen can hide a delete that would refuse")
    void theDefaultAccountIsMarked() {
        UserDto first = userService.search(new UserSearchRequest(null, 0, 50, "id", "asc"))
                .getContent().getFirst();

        assertThat(first.protectedAccount()).isTrue();
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
