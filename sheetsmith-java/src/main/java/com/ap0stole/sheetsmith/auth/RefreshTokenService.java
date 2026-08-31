package com.ap0stole.sheetsmith.auth;

import com.ap0stole.sheetsmith.configs.AuthConfig;
import com.ap0stole.sheetsmith.domain.entity.RefreshToken;
import com.ap0stole.sheetsmith.domain.entity.User;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import com.ap0stole.sheetsmith.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Issues, rotates and withdraws the long-lived half of a session.
 * <p>
 * Rotation is the whole design: every refresh consumes the token it was given and hands back a new
 * one with a fresh expiry. That produces "stays signed in while you keep using it" for free, and it
 * means a copied token stops working the moment the real owner refreshes next — whichever of the
 * two arrives second is refused.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final int TOKEN_BYTES = 32;

    private final AuthConfig authConfig;
    private final RefreshTokenRepository tokens;

    /** Where "now" comes from, so a test can decide what it is. */
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /** The plain token, returned once. Only its hash is kept, so this is the only chance to see it. */
    public record IssuedToken(String value, LocalDateTime expiresAt) {}

    /**
     * A new refresh token for this user.
     * <p>
     * Annotated for callers in other beans; {@link #rotate} calls {@link #mint} directly, because a
     * call through {@code this} never reaches the proxy — and rotation is already inside a
     * transaction of its own, which is the one that has to cover both halves of the swap.
     */
    @Transactional
    public IssuedToken issue(User user, boolean rememberMe) {
        return mint(user, rememberMe);
    }

    private IssuedToken mint(User user, boolean rememberMe) {
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        LocalDateTime expiresAt = LocalDateTime.now(clock).plus(
                rememberMe ? authConfig.getRememberMeTtl() : authConfig.getRefreshTokenTtl());
        tokens.save(RefreshToken.issue(user, hash(value), expiresAt, rememberMe));

        return new IssuedToken(value, expiresAt);
    }

    /**
     * Exchanges a token for the next one.
     * <p>
     * A token presented twice is not merely stale — the legitimate holder replaced it on first use,
     * so a second presentation means a copy exists. Every session that user has is ended, because
     * refusing this one request while leaving the thief's own token working would protect nobody.
     */
    @Transactional
    public Rotation rotate(String presented) {
        RefreshToken existing = tokens.findByTokenHash(hash(presented))
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "Session expired — sign in again"));

        LocalDateTime now = LocalDateTime.now(clock);
        if (existing.getUsedAt() != null) {
            log.warn("Refresh token for user {} was presented twice — every session of theirs is being "
                    + "ended, because a second use means a copy is in circulation", existing.getUser().getId());
            tokens.revokeAllForUser(existing.getUser().getId(), now);
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Session ended for safety — sign in again");
        }
        if (!existing.isUsable(now)) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Session expired — sign in again");
        }

        existing.setUsedAt(now);
        tokens.save(existing);

        User user = existing.getUser();
        return new Rotation(user, mint(user, existing.isRememberMe()));
    }

    public record Rotation(User user, IssuedToken token) {}

    /** Signing out: the token given up, and nothing else of that user's touched. */
    @Transactional
    public void revoke(String presented) {
        tokens.findByTokenHash(hash(presented)).ifPresent(token -> {
            token.setRevokedAt(LocalDateTime.now(clock));
            tokens.save(token);
        });
    }

    @Transactional
    public void revokeAllForUser(Long userId) {
        int ended = tokens.revokeAllForUser(userId, LocalDateTime.now(clock));
        if (ended > 0) {
            log.info("Ended {} session(s) for user {}", ended, userId);
        }
    }

    /**
     * SHA-256, not bcrypt: the value is already 256 bits of randomness, so there is nothing for a
     * slow hash to defend against, and this runs on every refresh.
     */
    private String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
