package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.auth.RefreshTokenService;
import com.ap0stole.sheetsmith.configs.AuthConfig;
import com.ap0stole.sheetsmith.domain.entity.User;
import com.ap0stole.sheetsmith.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The last resort, which has to work on a machine where nothing else does.
 * <p>
 * Every case here is a way it could quietly do the wrong thing: change the wrong account, apply a
 * password too short to be meant, leave a stolen session alive, or run when nobody asked.
 */
class AdminPasswordResetTest {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private User defaultAccount;
    private AuthConfig authConfig;
    private RefreshTokenService refreshTokens;
    private AdminPasswordReset reset;

    @BeforeEach
    void setUp() {
        defaultAccount = User.of("admin", encoder.encode("admin"));
        defaultAccount.setId(1L);
        defaultAccount.setMustChangePassword(true);

        UserRepository users = mock(UserRepository.class);
        when(users.findFirstIdOrderById()).thenReturn(1L);
        when(users.findById(1L)).thenReturn(Optional.of(defaultAccount));
        when(users.save(any())).thenAnswer(call -> call.getArgument(0));

        authConfig = new AuthConfig();
        refreshTokens = mock(RefreshTokenService.class);
        reset = new AdminPasswordReset(authConfig, users, encoder, refreshTokens);
    }

    @Test
    @DisplayName("unset, it does nothing at all")
    void doesNothingWhenNotAsked() {
        reset.applyIfRequested();

        assertThat(encoder.matches("admin", defaultAccount.getPasswordHash())).isTrue();
        verify(refreshTokens, never()).revokeAllForUser(any());
    }

    @Test
    @DisplayName("set, the default account takes the new password")
    void appliesTheRequestedPassword() {
        authConfig.setAdminPasswordReset("let-me-back-in");

        reset.applyIfRequested();

        assertThat(encoder.matches("let-me-back-in", defaultAccount.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("resetting ends every session of that account")
    void resettingEndsExistingSessions() {
        // A reset is used precisely when access may already be in the wrong hands. Leaving the
        // sessions alive would hand the new password to the owner and change nothing for anyone
        // else holding a token.
        authConfig.setAdminPasswordReset("let-me-back-in");

        reset.applyIfRequested();

        verify(refreshTokens).revokeAllForUser(1L);
    }

    @Test
    @DisplayName("a deliberate password answers the nag about the seeded one")
    void clearsTheMustChangeFlag() {
        authConfig.setAdminPasswordReset("chosen-on-purpose");

        reset.applyIfRequested();

        assertThat(defaultAccount.isMustChangePassword()).isFalse();
    }

    @Test
    @DisplayName("too short is refused, and the old password still works")
    void refusesATooShortPassword() {
        // Left alone rather than applied, so a typo can be corrected on the next start. Failing
        // startup outright would strand somebody whose only way in is this variable.
        authConfig.setAdminPasswordReset("ab");

        reset.applyIfRequested();

        assertThat(encoder.matches("admin", defaultAccount.getPasswordHash()))
                .as("the account must not be left with a password nobody meant to set")
                .isTrue();
        verify(refreshTokens, never()).revokeAllForUser(any());
    }

    @Test
    @DisplayName("blank is the same as unset, not a request to blank the password")
    void blankIsNotARequest() {
        authConfig.setAdminPasswordReset("   ");

        reset.applyIfRequested();

        assertThat(encoder.matches("admin", defaultAccount.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("with no accounts at all it says so instead of throwing")
    void survivesAnEmptyUsersTable() {
        UserRepository empty = mock(UserRepository.class);
        when(empty.findFirstIdOrderById()).thenReturn(null);
        authConfig.setAdminPasswordReset("let-me-back-in");

        new AdminPasswordReset(authConfig, empty, encoder, refreshTokens).applyIfRequested();

        verify(refreshTokens, never()).revokeAllForUser(any());
    }
}
