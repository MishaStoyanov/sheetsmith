package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.auth.RefreshTokenService;
import com.ap0stole.sheetsmith.configs.AuthConfig;
import com.ap0stole.sheetsmith.domain.entity.User;
import com.ap0stole.sheetsmith.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The way back in when the password is lost.
 * <p>
 * There is no email here and there never will be: this app is something people run for themselves,
 * and a self-hosted instance has no mail server to send a reset link from. Writing to the author is
 * no use either — nobody but the operator can reach their database, and that is a property worth
 * keeping rather than a gap to close.
 * <p>
 * So recovery belongs to whoever has the machine. Setting {@code SHEETSMITH_ADMIN_PASSWORD_RESET}
 * changes the default account's password once at startup, works identically under Docker and from
 * a bare jar, and says loudly what it did.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminPasswordReset {

    private static final int MIN_LENGTH = 4;

    private final AuthConfig authConfig;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokens;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void applyIfRequested() {
        String requested = authConfig.getAdminPasswordReset();
        if (requested == null || requested.isBlank()) {
            return;
        }

        if (requested.length() < MIN_LENGTH) {
            // Refused rather than applied, and the old password is left alone so the operator can
            // try again. Failing startup outright would strand somebody who simply mistyped.
            log.error("SHEETSMITH_ADMIN_PASSWORD_RESET is shorter than {} characters, so it was "
                    + "ignored and the password is unchanged. Set a longer one and restart.", MIN_LENGTH);
            return;
        }

        Long id = users.findFirstIdOrderById();
        if (id == null) {
            log.error("SHEETSMITH_ADMIN_PASSWORD_RESET was set, but there are no accounts to reset.");
            return;
        }

        User account = users.findById(id).orElseThrow();
        account.setPasswordHash(passwordEncoder.encode(requested));
        // They chose this password deliberately, so the nag about the seeded one has been answered.
        account.setMustChangePassword(false);
        users.save(account);

        // A reset is a "lock everyone out" event: it is used precisely when access may be in the
        // wrong hands, and leaving existing sessions alive would defeat the point of resetting.
        refreshTokens.revokeAllForUser(id);

        log.warn("Password for '{}' was reset from SHEETSMITH_ADMIN_PASSWORD_RESET, and every "
                + "session of that account was ended. REMOVE the variable now — while it is set, "
                + "every restart resets the password again and it sits in plain text in your "
                + "environment.", account.getName());
    }
}
