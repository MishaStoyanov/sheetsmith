package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.configs.AuthConfig;
import com.ap0stole.sheetsmith.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import com.ap0stole.sheetsmith.domain.entity.User;

/**
 * Says, once per boot, that this instance still lets anyone in as {@code admin}/{@code admin}.
 * <p>
 * Only when authentication is on: with it off the seeded row is unused, and warning about a
 * password nobody is asked for would be noise that teaches people to ignore the log.
 * <p>
 * Deliberately a log line and not part of {@code /api/capabilities}: that endpoint answers
 * unauthenticated callers, and "this instance still has the default password" is precisely the
 * sentence not to hand a stranger. The same fact reaches the person who <em>has</em> logged in
 * through their own profile, where it is advice rather than a tip-off.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminSeedCheck {

    private final AuthConfig authConfig;
    private final UserRepository userRepository;

    @EventListener(ApplicationReadyEvent.class)
    public void warnIfDefaultPasswordStands() {
        if (!authConfig.isEnabled()) {
            return;
        }
        userRepository.findByName("admin")
                .filter(User::isMustChangePassword)
                .ifPresent(admin -> log.warn("The 'admin' account still has its seeded password. "
                        + "Anyone who can reach this instance can sign in as admin/admin — change it."));
    }
}
