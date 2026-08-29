package com.ap0stole.sheetsmith.schema;

import com.ap0stole.sheetsmith.auth.AuthenticatedUser;
import com.ap0stole.sheetsmith.domain.dto.user.CreateUserRequest;
import com.ap0stole.sheetsmith.domain.dto.user.PatchUserRequest;
import com.ap0stole.sheetsmith.domain.dto.user.UserSearchRequest;
import com.ap0stole.sheetsmith.domain.enums.Role;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who may do what to whom.
 * <p>
 * Every check here goes through the service with a role in the security context rather than through
 * the interface, because the interface is not the guard. A screen that hides a button is a
 * convenience; the refusal has to hold for a request that never opened the screen.
 */
@SpringBootTest
@TestPropertySource(properties = "sheetsmith.auth.enabled=true")
@EnabledIf(value = "dockerAvailable", disabledReason = "Docker is not available")
class UserRolesTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserService userService;

    private Long seededId;
    private Long adminId;
    private Long plainId;

    @BeforeEach
    void seed() {
        jdbc.update("delete from refresh_tokens");
        jdbc.update("delete from users where name like 'roles-%'");

        // The seeded account is whichever row has the lowest id, which on a real instance is the
        // migration's admin. Here it is asserted rather than assumed.
        seededId = jdbc.queryForObject("select min(id) from users", Long.class);
        jdbc.update("update users set role = 'SUPERADMIN' where id = ?", seededId);

        adminId = insert("roles-admin", Role.ADMIN);
        plainId = insert("roles-plain", Role.USER);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private Long insert(String name, Role role) {
        jdbc.update("insert into users (name, password_hash, must_change_password, role) values (?, 'x', false, ?)",
                name, role.name());
        return jdbc.queryForObject("select id from users where name = ?", Long.class, name);
    }

    private void as(Long id, String name) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthenticatedUser(id, name), null, List.of()));
    }

    private Role roleOf(Long id) {
        return Role.valueOf(jdbc.queryForObject("select role from users where id = ?", String.class, id));
    }

    // ── What a plain user cannot do ───────────────────────────────────────────

    @Test
    @DisplayName("a plain user cannot create, edit or delete anybody")
    void plainUsersCannotManageAccounts() {
        as(plainId, "roles-plain");

        // Creating and deleting are rules about the endpoint, and are asked at it - see
        // SecurityMatrixTest. What is asked here is the rule that needs the row: who the caller is
        // pointing at, which no path can answer.
        assertThatThrownBy(() -> userService.update(adminId, new PatchUserRequest("renamed", null, null), plainId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("permission");
    }

    @Test
    @DisplayName("a plain user can still change their own name and password")
    void plainUsersOwnTheirOwnAccount() {
        // The check on update is inside the method rather than on it precisely because of this
        // case: one endpoint, two callers, different rights.
        as(plainId, "roles-plain");

        assertThat(userService.update(plainId, new PatchUserRequest("roles-plain-renamed", null, null), plainId).name())
                .isEqualTo("roles-plain-renamed");
    }

    @Test
    @DisplayName("a plain user can still read the list, because history's filter is built from it")
    void plainUsersCanReadTheList() {
        // Locking this down would quietly empty the "started by" filter on the history screen for
        // everybody who is not an administrator, and the names are on the analytics screen anyway.
        as(plainId, "roles-plain");

        assertThat(userService.search(new UserSearchRequest(null, null, null, null, null)).getContent())
                .isNotEmpty();
    }

    // ── The one-way door ──────────────────────────────────────────────────────

    @Test
    @DisplayName("an administrator can hand out administrator")
    void adminsCanPromote() {
        as(adminId, "roles-admin");

        userService.changeRole(plainId, Role.ADMIN, adminId);

        assertThat(roleOf(plainId)).isEqualTo(Role.ADMIN);
    }

    @Test
    @DisplayName("an administrator cannot take it back — that is the whole point of the door")
    void adminsCannotDemote() {
        as(adminId, "roles-admin");
        Long other = insert("roles-second-admin", Role.ADMIN);

        assertThatThrownBy(() -> userService.changeRole(other, Role.USER, adminId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not take it back");
        assertThat(roleOf(other)).isEqualTo(Role.ADMIN);
    }

    @Test
    @DisplayName("the seeded account can take it back")
    void theSeededAccountCanDemote() {
        as(seededId, "admin");

        userService.changeRole(adminId, Role.USER, seededId);

        assertThat(roleOf(adminId)).isEqualTo(Role.USER);
    }

    @Test
    @DisplayName("superadmin cannot be handed out")
    void superadminIsNotARankAnybodyCanBeGiven() {
        as(seededId, "admin");

        assertThatThrownBy(() -> userService.changeRole(adminId, Role.SUPERADMIN, seededId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cannot be given out");
    }

    @Test
    @DisplayName("the seeded account's own role cannot be changed, by anyone")
    void theSeededAccountKeepsItsRole() {
        as(seededId, "admin");

        assertThatThrownBy(() -> userService.changeRole(seededId, Role.USER, seededId))
                .isInstanceOf(ApiException.class);
        assertThat(roleOf(seededId)).isEqualTo(Role.SUPERADMIN);
    }

    @Test
    @DisplayName("nobody changes their own role")
    void noSelfPromotion() {
        as(adminId, "roles-admin");

        assertThatThrownBy(() -> userService.changeRole(adminId, Role.USER, adminId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("your own role");
    }

    @Test
    @DisplayName("a plain user cannot promote themselves by calling the endpoint directly")
    void plainUsersCannotReachTheRoleEndpoint() {
        as(plainId, "roles-plain");

        assertThatThrownBy(() -> userService.changeRole(plainId, Role.ADMIN, plainId))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a new account starts as a plain user")
    void newAccountsAreNotAdministrators() {
        as(seededId, "admin");

        assertThat(userService.create(new CreateUserRequest("roles-fresh", "password")).role())
                .isEqualTo(Role.USER);
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
