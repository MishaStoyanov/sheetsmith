package com.ap0stole.sheetsmith.schema;

import com.ap0stole.sheetsmith.domain.dto.user.CreateUserRequest;
import com.ap0stole.sheetsmith.domain.dto.user.UserSearchRequest;
import com.ap0stole.sheetsmith.domain.enums.Role;
import com.ap0stole.sheetsmith.services.UserService;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The trap this whole feature could have fallen into.
 * <p>
 * Authentication here is optional and off by default. With it off nobody is signed in, so a plain
 * {@code hasRole('ADMIN')} would deny every one of these calls and take single-user mode down
 * entirely — on the default configuration, which is the one most people will run. The rules read
 * the switch first and answer yes for an instance without accounts, because on such an instance the
 * person at the keyboard is the operator by definition.
 * <p>
 * This is a whole test class rather than one case because the failure it guards against is silent
 * and total: the application would start, the screens would load, and every attempt to manage
 * anything would be refused with no explanation.
 */
@SpringBootTest
@TestPropertySource(properties = "sheetsmith.auth.enabled=false")
@EnabledIf(value = "dockerAvailable", disabledReason = "Docker is not available")
class RolesWithoutAuthTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserService userService;

    @BeforeEach
    void clean() {
        SecurityContextHolder.clearContext();
        jdbc.update("delete from users where name like 'noauth-%'");
    }

    @AfterEach
    void tidy() {
        jdbc.update("delete from users where name like 'noauth-%'");
    }

    @Test
    @DisplayName("with no accounts, managing accounts still works")
    void managementIsNotBlockedWhenNobodyCanSignIn() {
        assertThatCode(() -> userService.create(new CreateUserRequest("noauth-someone", "password")))
                .doesNotThrowAnyException();

        Long id = jdbc.queryForObject("select id from users where name = 'noauth-someone'", Long.class);

        assertThatCode(() -> userService.delete(id, null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the list is readable, so the history filter is not quietly empty")
    void theListIsReadable() {
        assertThatCode(() -> userService.search(new UserSearchRequest(null, null, null, null, null)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("roles can still be handed out on an instance where nobody logs in")
    void rolesStillWork() {
        // Somebody may turn authentication on tomorrow, and the accounts they set up today have to
        // arrive with the roles they were given.
        userService.create(new CreateUserRequest("noauth-promoted", "password"));
        Long id = jdbc.queryForObject("select id from users where name = 'noauth-promoted'", Long.class);

        userService.changeRole(id, Role.ADMIN, null);

        assertThat(jdbc.queryForObject("select role from users where id = ?", String.class, id))
                .isEqualTo("ADMIN");
    }

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable _) {
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
