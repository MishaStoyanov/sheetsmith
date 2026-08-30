package com.ap0stole.sheetsmith.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.ZoneId;
import java.time.LocalDateTime;

/**
 * A way back in, and the reason refresh is not a JWT: this row can be taken away, where a signed
 * token can only be waited out.
 * <p>
 * Only the hash of the token is here. The value itself was handed to the browser once and is not
 * recoverable from the database.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    /** Chosen at sign-in and carried across every rotation: "remember me" must survive refreshing. */
    @Column(nullable = false)
    private boolean rememberMe;

    /** Set when this token was exchanged for the next one. A second attempt means it was copied. */
    private LocalDateTime usedAt;

    /** Set by signing out, or by anything that must end a session immediately. */
    private LocalDateTime revokedAt;

    public static RefreshToken issue(User user, String tokenHash, LocalDateTime expiresAt, boolean rememberMe) {
        RefreshToken token = new RefreshToken();
        token.user = user;
        token.tokenHash = tokenHash;
        token.createdAt = LocalDateTime.now(ZoneId.systemDefault());
        token.expiresAt = expiresAt;
        token.rememberMe = rememberMe;
        return token;
    }

    /** Usable exactly once, before it expires, and only until something withdrew it. */
    public boolean isUsable(LocalDateTime now) {
        return usedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }
}
