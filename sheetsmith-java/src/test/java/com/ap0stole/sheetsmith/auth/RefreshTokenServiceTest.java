package com.ap0stole.sheetsmith.auth;

import com.ap0stole.sheetsmith.configs.AuthConfig;
import com.ap0stole.sheetsmith.domain.entity.RefreshToken;
import com.ap0stole.sheetsmith.domain.entity.User;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Rotation, and what happens when a token turns up twice.
 * <p>
 * That second case is the reason the refresh half is a database row rather than a signed token:
 * being able to say "this one is finished" at all is what makes a stolen session endable.
 */
class RefreshTokenServiceTest {

    private final List<RefreshToken> stored = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong();

    private User user;
    private RefreshTokenService service;
    private AuthConfig authConfig;

    @BeforeEach
    void setUp() {
        user = User.of("dana", "hash");
        user.setId(7L);

        RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
        when(repository.save(any())).thenAnswer(call -> {
            RefreshToken token = call.getArgument(0);
            if (token.getId() == null) {
                token.setId(ids.incrementAndGet());
                stored.add(token);
            }
            return token;
        });
        when(repository.findByTokenHash(anyString())).thenAnswer(call -> {
            String hash = call.getArgument(0);
            return stored.stream().filter(token -> token.getTokenHash().equals(hash)).findFirst();
        });
        when(repository.revokeAllForUser(any(), any())).thenAnswer(call -> {
            LocalDateTime now = call.getArgument(1);
            int count = 0;
            for (RefreshToken token : stored) {
                if (token.getUser().getId().equals(call.getArgument(0))
                        && token.getRevokedAt() == null && token.getUsedAt() == null) {
                    token.setRevokedAt(now);
                    count++;
                }
            }
            return count;
        });

        authConfig = new AuthConfig();
        service = new RefreshTokenService(authConfig, repository, java.time.Clock.systemDefaultZone());
    }

    @Test
    @DisplayName("the token is handed over once and only its hash is kept")
    void onlyTheHashIsStored() {
        RefreshTokenService.IssuedToken issued = service.issue(user, false);

        assertThat(issued.value()).isNotBlank();
        assertThat(stored).singleElement().satisfies(token ->
                assertThat(token.getTokenHash())
                        .as("a leaked database must not yield a usable token")
                        .isNotEqualTo(issued.value()));
    }

    @Test
    @DisplayName("refreshing replaces the token and pushes the expiry out")
    void rotationRenewsTheSession() {
        RefreshTokenService.IssuedToken first = service.issue(user, false);

        RefreshTokenService.Rotation rotation = service.rotate(first.value());

        assertThat(rotation.token().value()).isNotEqualTo(first.value());
        assertThat(rotation.user().getId()).isEqualTo(7L);
        // "Stays signed in while you keep using it" falls out of rotation rather than being a
        // separate mechanism: each exchange dates the new token from now.
        assertThat(rotation.token().expiresAt()).isAfterOrEqualTo(first.expiresAt());
    }

    @Test
    @DisplayName("remember me survives every rotation")
    void rememberMeIsCarriedForward() {
        RefreshTokenService.IssuedToken first = service.issue(user, true);
        LocalDateTime monthish = LocalDateTime.now().plusDays(29);

        RefreshTokenService.Rotation rotation = service.rotate(first.value());

        assertThat(rotation.token().expiresAt())
                .as("losing the flag on refresh would silently demote a 30-day session to a day")
                .isAfter(monthish);
    }

    @Test
    @DisplayName("the token just spent cannot be spent again")
    void aUsedTokenIsRefused() {
        RefreshTokenService.IssuedToken first = service.issue(user, false);
        service.rotate(first.value());

        var firstValue = first.value();
        assertThatThrownBy(() -> service.rotate(firstValue)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a token presented twice ends every session that user has")
    void replayEndsAllSessions() {
        // The legitimate holder replaced this token on first use, so a second presentation means a
        // copy is in circulation. Refusing only this request would leave the thief's own freshly
        // rotated token working, which protects nobody.
        RefreshTokenService.IssuedToken first = service.issue(user, false);
        RefreshTokenService.Rotation rotation = service.rotate(first.value());

        var firstValue = first.value();
        assertThatThrownBy(() -> service.rotate(firstValue)).isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> service.rotate(rotation.token().value()))
                .as("the token the thief would be holding must stop working too")
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("an expired token is refused")
    void anExpiredTokenIsRefused() {
        authConfig.setRefreshTokenTtl(Duration.ofMillis(-1));
        RefreshTokenService.IssuedToken expired = service.issue(user, false);

        var expiredValue = expired.value();
        assertThatThrownBy(() -> service.rotate(expiredValue))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("signing out gives up one token, not the whole account")
    void signOutRevokesOnlyThePresentedToken() {
        RefreshTokenService.IssuedToken laptop = service.issue(user, false);
        RefreshTokenService.IssuedToken phone = service.issue(user, false);

        service.revoke(laptop.value());

        var laptopValue = laptop.value();
        assertThatThrownBy(() -> service.rotate(laptopValue)).isInstanceOf(ApiException.class);
        assertThat(service.rotate(phone.value()).token().value())
                .as("signing out on one device must not sign you out on the others")
                .isNotBlank();
    }

    @Test
    @DisplayName("a token nobody issued is refused rather than trusted")
    void anUnknownTokenIsRefused() {
        assertThatThrownBy(() -> service.rotate("made-up-token")).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("ending every session of a user's takes the way back in with it")
    void revokeAllEndsEverySession() {
        RefreshTokenService.IssuedToken laptop = service.issue(user, false);
        RefreshTokenService.IssuedToken phone = service.issue(user, true);

        service.revokeAllForUser(7L);

        var laptopValue = laptop.value();
        assertThatThrownBy(() -> service.rotate(laptopValue)).isInstanceOf(ApiException.class);
        var phoneValue = phone.value();
        assertThatThrownBy(() -> service.rotate(phoneValue)).isInstanceOf(ApiException.class);
    }
}
