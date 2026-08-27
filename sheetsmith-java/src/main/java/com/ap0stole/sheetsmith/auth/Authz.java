package com.ap0stole.sheetsmith.auth;

import com.ap0stole.sheetsmith.configs.AuthConfig;
import com.ap0stole.sheetsmith.domain.enums.Role;
import com.ap0stole.sheetsmith.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The authorisation rules, in one bean that the annotations point at.
 * <p>
 * <strong>Why a bean rather than {@code hasRole('ADMIN')} in the annotation.</strong> Authentication
 * on this instance is optional, and with it off nobody is signed in at all — so a plain role
 * expression would deny every one of these calls and take the entire single-user mode down with it.
 * That is not a hypothetical: it is the default configuration. Here the switch is read first and the
 * answer for an instance without accounts is yes, because on such an instance the person at the
 * keyboard is the operator by definition.
 * <p>
 * The role is read from the database rather than from the access token. A token carries what was
 * true when it was issued, and with a two-hour lifetime that means a demotion — or a deletion —
 * would keep working for the rest of the afternoon. One lookup by primary key per request is a
 * cheap price for "revoked means revoked".
 */
@Component("authz")
@RequiredArgsConstructor
public class Authz {

    private final AuthConfig authConfig;
    private final CurrentUser currentUser;
    private final UserRepository users;

    /** May manage other accounts. */
    public boolean admin() {
        return has(Role::manages);
    }

    /** May take authority away again, which only the seeded account can do. */
    public boolean superadmin() {
        return has(role -> role == Role.SUPERADMIN);
    }

    /** Signed in at all — or on an instance where signing in is not a thing. */
    public boolean signedIn() {
        return !authConfig.isEnabled() || currentUser.id().isPresent();
    }

    /**
     * Whether the caller may see what a particular person has spent.
     * <p>
     * Not the same shape as the other rules here, and deliberately so. Money spent is somebody's
     * activity rather than a setting, so the ladder is: your own, always. A superadmin, everybody.
     * An administrator, the ordinary users they are there to look after — <strong>but not their
     * peers</strong>, because "manages accounts" was never meant to mean "reads the other
     * administrators".
     * <p>
     * With authentication off there are no accounts and no privacy boundary to draw: the person at
     * the keyboard is the only person there is.
     */
    public boolean maySeeSpendOf(Long targetId, Role targetRole) {
        if (!authConfig.isEnabled()) {
            return true;
        }
        Long caller = currentUser.id().orElse(null);
        if (caller == null) {
            return false;
        }
        if (caller.equals(targetId)) {
            return true;
        }
        return role()
                .map(mine -> mine == Role.SUPERADMIN || (mine == Role.ADMIN && targetRole == Role.USER))
                .orElse(false);
    }

    /** The caller's role, or empty where there is nobody to have one. */
    public java.util.Optional<Role> role() {
        return currentUser.id().flatMap(users::findById).map(com.ap0stole.sheetsmith.domain.entity.User::getRole);
    }

    private boolean has(java.util.function.Predicate<Role> test) {
        if (!authConfig.isEnabled()) {
            return true;
        }
        return role().filter(test).isPresent();
    }
}
