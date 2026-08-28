package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.auth.Authz;
import com.ap0stole.sheetsmith.auth.CurrentUser;
import com.ap0stole.sheetsmith.configs.AuthConfig;
import com.ap0stole.sheetsmith.domain.entity.JobRecord;
import com.ap0stole.sheetsmith.domain.entity.User;
import com.ap0stole.sheetsmith.domain.enums.Role;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import jakarta.persistence.criteria.JoinType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Whose work a caller may see.
 * <p>
 * The ladder is the same one the rest of the application uses, read here as "your own work is
 * yours": a user sees their own runs, an administrator sees theirs and every ordinary user's, and
 * the superadmin sees everything. An administrator does not see their peers, for the same reason
 * they cannot read a peer's spending — managing accounts was never meant to mean reading the other
 * administrators' work.
 * <p>
 * <strong>Runs with no owner</strong> go to administrators and above but not to users. Every run
 * made before authentication was switched on is ownerless, and there is no honest way to decide
 * whose they were; showing them to everybody would hand a new user somebody else's history, and
 * hiding them from everybody would quietly delete the past from the interface.
 * <p>
 * Applied as a filter rather than as a check after the fact, because a page of twenty rows has to
 * be twenty rows the caller may see — trimming afterwards leaves short pages and a page count that
 * counts what was hidden.
 */
@Component
@RequiredArgsConstructor
public class WorkVisibility {

    private final AuthConfig authConfig;
    private final CurrentUser currentUser;
    private final Authz authz;

    /**
     * The filter to apply to every history query.
     * <p>
     * A left join, deliberately: the association is optional, and an inner one would drop the
     * ownerless runs before the rule above ever got to decide about them.
     */
    public Specification<JobRecord> readable() {
        return (root, query, cb) -> {
            if (!authConfig.isEnabled()) {
                // No accounts, so no boundary to draw. The person at the keyboard is everybody.
                return cb.conjunction();
            }
            Long me = currentUser.id().orElse(null);
            if (me == null) {
                return cb.disjunction();
            }
            Role mine = authz.role().orElse(Role.USER);
            if (mine == Role.SUPERADMIN) {
                return cb.conjunction();
            }

            var owner = root.join("startedBy", JoinType.LEFT);
            var isMine = cb.equal(owner.get("id"), me);
            if (mine == Role.ADMIN) {
                return cb.or(isMine,
                        cb.equal(owner.get("role"), Role.USER),
                        cb.isNull(root.get("startedBy")));
            }
            return isMine;
        };
    }

    /**
     * The same rule as a SQL fragment, for the figures.
     * <p>
     * Analytics is the same information by a different door: hiding somebody's runs from the
     * history while their totals stay readable on a chart would hide the rows and publish the
     * summary. The clause is a subquery rather than a fetched list of ids, so a thousand accounts
     * still make one query.
     *
     * @param column the user column as it is named in the query, prefix and all
     */
    public Clause forUserColumn(String column) {
        if (!authConfig.isEnabled()) {
            return Clause.EVERYTHING;
        }
        Long me = currentUser.id().orElse(null);
        if (me == null) {
            return new Clause("1 = 0", List.of());
        }
        Role mine = authz.role().orElse(Role.USER);
        if (mine == Role.SUPERADMIN) {
            return Clause.EVERYTHING;
        }
        if (mine == Role.ADMIN) {
            return new Clause("(" + column + " = ?"
                    + " or " + column + " in (select id from users where role = 'USER')"
                    + " or " + column + " is null)", List.of(me));
        }
        return new Clause(column + " = ?", List.of(me));
    }

    /** A piece of a where clause, or nothing at all when everything is visible. */
    public record Clause(String sql, List<Object> args) {

        static final Clause EVERYTHING = new Clause(null, List.of());

        public boolean restricts() {
            return sql != null;
        }
    }

    /** The same rule for one run already in hand, for the endpoints that take an id. */
    public boolean mayRead(JobRecord job) {
        return mayReadWorkOf(job.getStartedBy());
    }

    /**
     * And for a document session, which had no rule at all.
     * <p>
     * A session is somebody's spreadsheet open on a desk: its rows, its revisions and its undo
     * history. Every endpoint under {@code /api/chat/sessions} took an id and served whoever asked,
     * so with an id in hand any signed-in person could read, edit, revert or download a colleague's
     * document. The history had this rule from the day runs got owners; the sessions the runs work
     * on did not.
     */
    public boolean mayRead(com.ap0stole.sheetsmith.domain.entity.DocumentSession session) {
        return mayReadWorkOf(session.getUser());
    }

    private boolean mayReadWorkOf(User owner) {
        if (!authConfig.isEnabled()) {
            return true;
        }
        Long me = currentUser.id().orElse(null);
        if (me == null) {
            return false;
        }
        Role mine = authz.role().orElse(Role.USER);
        if (mine == Role.SUPERADMIN) {
            return true;
        }
        if (owner != null && me.equals(owner.getId())) {
            return true;
        }
        return mine == Role.ADMIN && (owner == null || owner.getRole() == Role.USER);
    }

    /**
     * Refuses as "not found" rather than "forbidden".
     * <p>
     * A run somebody may not see should not be distinguishable from one that never existed —
     * otherwise the difference between the two answers is a way to count other people's work.
     */
    public void requireReadable(JobRecord job) {
        if (!mayRead(job)) {
            throw new ApiException(ErrorCode.JOB_NOT_FOUND, "Job not found: " + job.getId());
        }
    }
}
