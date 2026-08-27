package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.auth.RefreshTokenService;
import com.ap0stole.sheetsmith.domain.dto.user.CreateUserRequest;
import com.ap0stole.sheetsmith.domain.dto.user.PatchUserRequest;
import com.ap0stole.sheetsmith.domain.dto.user.ReplaceUserRequest;
import com.ap0stole.sheetsmith.domain.dto.user.UserSearchRequest;
import com.ap0stole.sheetsmith.domain.entity.User;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rules that stop an instance being locked out of itself, and the one that stops someone taking
 * over an account they merely walked up to.
 */
class UserServiceTest {

    private final List<User> stored = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong();
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private User defaultAdmin;
    private User dana;
    private RefreshTokenService refreshTokens;
    private UserService service;

    @BeforeEach
    void setUp() {
        UserRepository users = mock(UserRepository.class);
        when(users.save(any())).thenAnswer(call -> {
            User user = call.getArgument(0);
            if (user.getId() == null) {
                user.setId(ids.incrementAndGet());
                stored.add(user);
            }
            return user;
        });
        when(users.findById(any())).thenAnswer(call ->
                stored.stream().filter(u -> u.getId().equals(call.getArgument(0))).findFirst());
        when(users.findByName(anyString())).thenAnswer(call ->
                stored.stream().filter(u -> u.getName().equals(call.<String>getArgument(0))).findFirst());
        when(users.findFirstIdOrderById()).thenAnswer(call ->
                stored.stream().map(User::getId).min(Long::compareTo).orElse(null));
        when(users.search(any(), any())).thenAnswer(call ->
                new org.springframework.data.domain.PageImpl<>(stored));
        doDelete(users);

        refreshTokens = mock(RefreshTokenService.class);

        // These cases are about the rules that hold whoever is asking — a name already taken, the
        // seeded account, your own password. Authorisation has its own test against a real security
        // context, so here the caller is simply allowed.
        com.ap0stole.sheetsmith.auth.Authz authz = mock(com.ap0stole.sheetsmith.auth.Authz.class);
        when(authz.admin()).thenReturn(true);
        when(authz.superadmin()).thenReturn(true);

        service = new UserService(users, encoder, refreshTokens, authz,
                mock(com.ap0stole.sheetsmith.services.BudgetService.class),
                mock(com.ap0stole.sheetsmith.services.BudgetRequestService.class));

        defaultAdmin = persist("admin", "admin");
        dana = persist("dana", "correct-horse");
    }

    private void doDelete(UserRepository users) {
        org.mockito.Mockito.doAnswer(call -> {
            stored.remove(call.<User>getArgument(0));
            return null;
        }).when(users).delete(any());
    }

    private User persist(String name, String password) {
        User user = User.of(name, encoder.encode(password));
        user.setId(ids.incrementAndGet());
        stored.add(user);
        return user;
    }

    @Test
    @DisplayName("the default account cannot be deleted — it is the way back in")
    void theDefaultAccountIsProtected() {
        assertThatThrownBy(() -> service.delete(defaultAdmin.getId(), dana.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cannot be deleted");

        assertThat(stored).contains(defaultAdmin);
    }

    @Test
    @DisplayName("being the default account is about being first, not about being called admin")
    void renamingTheDefaultAccountDoesNotUnprotectIt() {
        // A rule a rename can switch off is not a rule.
        service.replace(defaultAdmin.getId(), new ReplaceUserRequest("owner", "new-password"));

        assertThatThrownBy(() -> service.delete(defaultAdmin.getId(), dana.getId()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("you cannot delete the account you are signed in with")
    void youCannotDeleteYourself() {
        assertThatThrownBy(() -> service.delete(dana.getId(), dana.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("signed in with");
    }

    @Test
    @DisplayName("deleting somebody takes their way back in with them")
    void deletingRevokesTheirSessions() {
        User temp = persist("temp", "pw");

        service.delete(temp.getId(), dana.getId());

        // Without this they keep a working session for as long as their refresh token lives, which
        // is up to thirty days after being removed from the instance.
        verify(refreshTokens).revokeAllForUser(temp.getId());
        assertThat(stored).doesNotContain(temp);
    }

    @Test
    @DisplayName("changing your own password needs the current one")
    void changingYourOwnPasswordNeedsProof() {
        // Someone who found the screen unlocked must not be able to lock the owner out.
        assertThatThrownBy(() -> service.update(dana.getId(),
                new PatchUserRequest(null, "hijacked", null), dana.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("current password");

        assertThatThrownBy(() -> service.update(dana.getId(),
                new PatchUserRequest(null, "hijacked", "wrong"), dana.getId()))
                .isInstanceOf(ApiException.class);

        service.update(dana.getId(), new PatchUserRequest(null, "chosen", "correct-horse"), dana.getId());
        assertThat(encoder.matches("chosen", dana.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("resetting somebody else's password does not, because there is nothing to prove")
    void anAdminResetNeedsNoCurrentPassword() {
        service.update(dana.getId(), new PatchUserRequest(null, "reset-by-admin", null), defaultAdmin.getId());

        assertThat(encoder.matches("reset-by-admin", dana.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("changing a password answers the nag, whatever it was set for")
    void changingThePasswordClearsTheFlag() {
        defaultAdmin.setMustChangePassword(true);

        service.update(defaultAdmin.getId(), new PatchUserRequest(null, "something-else", null), dana.getId());

        assertThat(defaultAdmin.isMustChangePassword()).isFalse();
    }

    @Test
    @DisplayName("changing a password ends every session that account had")
    void changingAPasswordEndsTheSessions() {
        // A password is changed when the old one is thought to be known to somebody else. Leaving
        // the sessions alive would give the owner a new password and take nothing away from anyone
        // already holding a token, which is the opposite of the point.
        service.update(dana.getId(), new PatchUserRequest(null, "reset-by-admin", null), defaultAdmin.getId());

        verify(refreshTokens).revokeAllForUser(dana.getId());
    }

    @Test
    @DisplayName("renaming somebody does not end their sessions")
    void renamingLeavesSessionsAlone() {
        // The token names them by id, so a rename is not a credential change and signing them out
        // over it would be gratuitous.
        service.update(dana.getId(), new PatchUserRequest("dana-renamed", null, null), defaultAdmin.getId());

        verify(refreshTokens, never()).revokeAllForUser(any());
    }

    @Test
    @DisplayName("two people cannot share a name, and keeping your own is not a clash")
    void namesStayUnique() {
        assertThatThrownBy(() -> service.create(new CreateUserRequest("dana", "pw")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already called");

        // Renaming yourself to what you are already called must not collide with yourself.
        service.update(dana.getId(), new PatchUserRequest("dana", null, null), dana.getId());
        assertThat(dana.getName()).isEqualTo("dana");
    }

    @Test
    @DisplayName("a patch leaves out what it did not mention")
    void patchOnlyTouchesWhatItNames() {
        String before = dana.getPasswordHash();

        service.update(dana.getId(), new PatchUserRequest("dana-renamed", null, null), defaultAdmin.getId());

        assertThat(dana.getName()).isEqualTo("dana-renamed");
        assertThat(dana.getPasswordHash()).isEqualTo(before);
    }

    @Test
    @DisplayName("sorting is an allowlist, not whatever the caller sends")
    void sortFieldsAreLimited() {
        // A Sort built from user input reaches any property of the entity — here that would mean
        // ordering the user list by password hash and learning something from the order.
        assertThatThrownBy(() -> service.search(new UserSearchRequest(null, 0, 20, "passwordHash", "asc")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Cannot sort users by");

        assertThat(service.search(new UserSearchRequest(null, 0, 20, "name", "asc"))).isNotEmpty();
    }

    @Test
    @DisplayName("creating never stores what was typed")
    void passwordsAreHashed() {
        service.create(new CreateUserRequest("erin", "hunter2"));

        User erin = stored.stream().filter(u -> u.getName().equals("erin")).findFirst().orElseThrow();
        assertThat(erin.getPasswordHash()).isNotEqualTo("hunter2");
        assertThat(encoder.matches("hunter2", erin.getPasswordHash())).isTrue();
        verify(refreshTokens, never()).revokeAllForUser(any());
    }
}
