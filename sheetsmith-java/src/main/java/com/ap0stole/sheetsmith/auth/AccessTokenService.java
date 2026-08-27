package com.ap0stole.sheetsmith.auth;

import com.ap0stole.sheetsmith.configs.AuthConfig;
import com.ap0stole.sheetsmith.domain.entity.User;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;

/**
 * Issues and reads the short-lived access token.
 * <p>
 * The subject is the user's id rather than their name: a rename must not invalidate a token, and
 * nothing downstream should have to resolve a name to find out who is asking. The name rides along
 * as a claim purely so the UI can greet someone without another round trip.
 */
@Service
@RequiredArgsConstructor
public class AccessTokenService {

    private static final MacAlgorithm ALGORITHM = MacAlgorithm.HS256;
    static final String NAME_CLAIM = "name";

    private final AuthConfig authConfig;
    private final JwtSecretProvider secretProvider;

    private volatile JwtEncoder encoder;
    private volatile JwtDecoder decoder;

    public String issue(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(String.valueOf(user.getId()))
                .claim(NAME_CLAIM, user.getName())
                .issuedAt(now)
                .expiresAt(now.plus(authConfig.getAccessTokenTtl()))
                .build();

        JwsHeader header = JwsHeader.with(ALGORITHM).build();
        return encoder().encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** @throws JwtException when the token is forged, altered or past its expiry */
    public Jwt decode(String token) {
        return decoder().decode(token);
    }

    // Built lazily rather than as beans: the key may be generated on first use, which needs the
    // database, and a bean graph that reaches the database while it is still being wired is a
    // startup failure waiting for the first person who runs against an empty one.
    private JwtEncoder encoder() {
        JwtEncoder existing = encoder;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (encoder == null) {
                encoder = new NimbusJwtEncoder(new ImmutableSecret<>(secretProvider.signingKey()));
            }
            return encoder;
        }
    }

    private JwtDecoder decoder() {
        JwtDecoder existing = decoder;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (decoder == null) {
                decoder = NimbusJwtDecoder
                        .withSecretKey(new SecretKeySpec(secretProvider.signingKey(), ALGORITHM.getName()))
                        .macAlgorithm(ALGORITHM)
                        .build();
            }
            return decoder;
        }
    }
}
