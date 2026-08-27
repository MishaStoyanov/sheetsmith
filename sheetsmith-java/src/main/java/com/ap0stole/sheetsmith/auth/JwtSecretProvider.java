package com.ap0stole.sheetsmith.auth;

import com.ap0stole.sheetsmith.configs.AuthConfig;
import com.ap0stole.sheetsmith.domain.entity.AuthSecret;
import com.ap0stole.sheetsmith.repository.AuthSecretRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * The key access tokens are signed with: the operator's, or one the instance made for itself.
 * <p>
 * Generating a fresh key on every boot would be simpler and is the wrong behaviour — it signs
 * everyone out on every restart, which reads as a bug rather than as a policy. So a generated key
 * is written down once and reused.
 * <p>
 * It lives in the database rather than in a file because the database is the thing an operator
 * already backs up, and a key that survives the backup is a key that survives a move. Supplying
 * {@code SHEETSMITH_JWT_SECRET} is still the right answer for more than one instance sharing a
 * database, since only then do they agree on tokens.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtSecretProvider {

    private static final String SECRET_NAME = "jwt-signing-key";
    private static final int KEY_BYTES = 32;

    private final AuthConfig authConfig;
    private final AuthSecretRepository secrets;

    /** HMAC-SHA256 needs at least 256 bits; a shorter supplied key is refused rather than padded. */
    public byte[] signingKey() {
        String configured = authConfig.getJwtSecret();
        if (configured != null && !configured.isBlank()) {
            byte[] key = configured.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (key.length < KEY_BYTES) {
                throw new IllegalStateException("SHEETSMITH_JWT_SECRET is too short: HMAC-SHA256 needs at least "
                        + KEY_BYTES + " bytes, got " + key.length
                        + ". Quietly padding it would make a weak key look like a strong one.");
            }
            return key;
        }
        return Base64.getDecoder().decode(storedOrGenerated());
    }

    private String storedOrGenerated() {
        return secrets.findById(SECRET_NAME)
                .map(AuthSecret::getSecret)
                .orElseGet(this::generateAndStore);
    }

    private String generateAndStore() {
        byte[] key = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(key);
        String encoded = Base64.getEncoder().encodeToString(key);
        secrets.save(AuthSecret.of(SECRET_NAME, encoded));

        log.info("Generated a signing key for access tokens and stored it. Set SHEETSMITH_JWT_SECRET "
                + "instead if more than one instance shares this database.");
        return encoded;
    }
}
