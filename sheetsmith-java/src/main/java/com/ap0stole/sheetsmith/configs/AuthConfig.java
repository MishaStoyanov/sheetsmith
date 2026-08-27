package com.ap0stole.sheetsmith.configs;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Whether this instance asks who you are.
 * <p>
 * Off by default, and that is the point rather than a shortcut: someone running this alone on their
 * own machine gains nothing from a login screen, and making them pass one would be a cost with no
 * matching risk. Turning it on is for the other case — an instance more than one person reaches.
 * <p>
 * Every endpoint therefore has two behaviours, not one, and the difference is visible from the very
 * first line of code that depends on it. A run made with the switch off has no owner and says so by
 * leaving the column null; it does not invent a "local" user, because an audit that makes up who
 * did something is worse than one that admits it does not know.
 */
@Slf4j
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "sheetsmith.auth")
public class AuthConfig {

    /** Off: no login screen, no owner on a run, no user management. Exactly today's behaviour. */
    private boolean enabled = false;

    @PostConstruct
    public void announce() {
        if (enabled) {
            log.info("Authentication ENABLED: /api/** requires a token, and every run records who asked for it");
            // Removed by the commit that adds POST /api/auth/login. Until then, turning this on
            // locks the instance rather than protecting it, and saying so at startup is cheaper
            // than letting someone discover it from a screen full of 401s.
            log.warn("...but this build has no login endpoint yet, so /api/** will refuse every "
                    + "request. Set SHEETSMITH_AUTH_ENABLED=false unless you are working on it.");
        } else {
            log.info("Authentication is off — anyone who can reach this instance can drive it. "
                    + "Set SHEETSMITH_AUTH_ENABLED=true if more than one person uses it.");
        }
    }
}
