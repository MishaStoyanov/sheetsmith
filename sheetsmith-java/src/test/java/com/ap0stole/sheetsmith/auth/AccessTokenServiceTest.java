package com.ap0stole.sheetsmith.auth;

import com.ap0stole.sheetsmith.configs.AuthConfig;
import com.ap0stole.sheetsmith.domain.entity.AuthSecret;
import com.ap0stole.sheetsmith.domain.entity.User;
import com.ap0stole.sheetsmith.repository.AuthSecretRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The signed half of a session, and the key it is signed with.
 * <p>
 * The key case is the one worth having: generating a fresh key per boot is the obvious shortcut and
 * silently signs everyone out on every restart, which arrives as a bug report about random logouts
 * rather than as a security decision anybody made.
 */
class AccessTokenServiceTest {

    private final Map<String, AuthSecret> secrets = new HashMap<>();

    private AuthConfig authConfig;
    private AuthSecretRepository repository;

    @BeforeEach
    void setUp() {
        authConfig = new AuthConfig();

        repository = mock(AuthSecretRepository.class);
        when(repository.save(any())).thenAnswer(call -> {
            AuthSecret secret = call.getArgument(0);
            secrets.put(secret.getName(), secret);
            return secret;
        });
        when(repository.findById(anyString()))
                .thenAnswer(call -> Optional.ofNullable(secrets.get(call.<String>getArgument(0))));
    }

    private AccessTokenService service() {
        return new AccessTokenService(authConfig, new JwtSecretProvider(authConfig, repository));
    }

    @Test
    @DisplayName("a token names the user by id, with the name only along for the ride")
    void carriesTheUserId() {
        User user = User.of("dana", "hash");
        user.setId(42L);

        Jwt decoded = service().decode(service().issue(user));

        // Id rather than name: renaming someone must not invalidate their token, and nothing
        // downstream should have to resolve a name to find out who is asking.
        assertThat(decoded.getSubject()).isEqualTo("42");
        assertThat(decoded.getClaimAsString("name")).isEqualTo("dana");
    }

    @Test
    @DisplayName("the expiry is the configured one, because nothing can withdraw the token early")
    void expiryFollowsTheSetting() {
        authConfig.setAccessTokenTtl(Duration.ofHours(2));
        User user = User.of("dana", "hash");
        user.setId(1L);

        Jwt decoded = service().decode(service().issue(user));

        assertThat(decoded.getExpiresAt())
                .isCloseTo(Instant.now().plus(Duration.ofHours(2)), within(1, java.time.temporal.ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("a token signed with another key is refused")
    void aForgedTokenIsRefused() {
        User user = User.of("dana", "hash");
        user.setId(1L);
        String issuedElsewhere = service().issue(user);

        // A second instance with its own generated key must not accept the first one's tokens.
        secrets.clear();
        AccessTokenService other = service();

        assertThatThrownBy(() -> other.decode(issuedElsewhere)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("an expired token is refused")
    void anExpiredTokenIsRefused() {
        // Built by hand rather than by issuing with a negative lifetime, for two reasons the API
        // teaches only when you try it: Nimbus refuses to sign a token that expires before it was
        // issued, and the decoder tolerates a minute of clock skew — so "expired" has to mean
        // comfortably past, not a millisecond past, or this test would pass on a broken decoder.
        Instant issued = Instant.now().minus(Duration.ofMinutes(10));
        String stale = new NimbusJwtEncoder(new ImmutableSecret<>(keyInUse()))
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(),
                        JwtClaimsSet.builder()
                                .subject("1")
                                .issuedAt(issued)
                                .expiresAt(issued.plus(Duration.ofMinutes(5)))
                                .build()))
                .getTokenValue();

        assertThatThrownBy(() -> service().decode(stale)).isInstanceOf(JwtException.class);
    }

    /** The key the provider settled on, so a hand-built token is signed the same way a real one is. */
    private byte[] keyInUse() {
        return new JwtSecretProvider(authConfig, repository).signingKey();
    }

    @Test
    @DisplayName("the generated key is kept, so a restart does not sign everyone out")
    void theGeneratedKeySurvivesARestart() {
        User user = User.of("dana", "hash");
        user.setId(1L);
        String beforeRestart = service().issue(user);

        // A new service over the same store stands in for the next boot.
        assertThat(service().decode(beforeRestart).getSubject()).isEqualTo("1");
        assertThat(secrets).as("the key is written down exactly once").hasSize(1);
    }

    @Test
    @DisplayName("a configured key is used as given, and a short one is refused rather than padded")
    void aSuppliedKeyIsTakenSeriously() {
        authConfig.setJwtSecret("this-key-is-exactly-long-enough-for-hmac256");
        User user = User.of("dana", "hash");
        user.setId(1L);

        assertThat(service().decode(service().issue(user)).getSubject()).isEqualTo("1");
        assertThat(secrets).as("a supplied key must not cause one to be generated too").isEmpty();

        authConfig.setJwtSecret("too-short");
        assertThatThrownBy(() -> service().issue(user))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least");
    }
}
