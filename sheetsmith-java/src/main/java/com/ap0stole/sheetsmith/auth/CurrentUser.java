package com.ap0stole.sheetsmith.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Who is asking, if anyone is.
 * <p>
 * Empty is a legitimate answer rather than a failure: with authentication off nobody is signed in,
 * and a run made then has no owner. Callers record that as null instead of inventing a local user —
 * an audit that makes up who did something is worse than one that admits it does not know.
 */
@Component
public class CurrentUser {

    public Optional<AuthenticatedUser> get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            return Optional.empty();
        }
        return Optional.of(user);
    }

    public Optional<Long> id() {
        return get().map(AuthenticatedUser::id);
    }
}
