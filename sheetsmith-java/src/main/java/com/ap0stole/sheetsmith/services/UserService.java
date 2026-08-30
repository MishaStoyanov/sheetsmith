package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.auth.RefreshTokenService;
import com.ap0stole.sheetsmith.domain.dto.user.*;
import com.ap0stole.sheetsmith.auth.Authz;
import com.ap0stole.sheetsmith.domain.entity.User;
import com.ap0stole.sheetsmith.domain.enums.Role;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import com.ap0stole.sheetsmith.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/** Managing accounts, and the three rules that stop an instance being locked out of itself. */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    /**
     * What a caller may sort by. An allowlist rather than passing the string to {@link Sort},
     * because a Sort built from user input reaches any property of the entity and its relations —
     * here that would mean ordering the user list by password hash, and learning something from the
     * order.
     */
    private static final Set<String> SORTABLE = Set.of("id", "name");
    private static final int MAX_PAGE_SIZE = 200;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokens;
    private final Authz authz;
    private final BudgetService budgets;
    private final BudgetRequestService budgetRequests;

    /**
     * Deliberately open to anyone signed in, unlike everything else here.
     * <p>
     * The history screen fills its "started by" filter from this list, so locking it to
     * administrators would quietly empty a filter ordinary people use — and the names are not a
     * secret anyway: the analytics screen prints them beside what each person spent.
     */
    public Page<UserDto> search(UserSearchRequest request) {
        Long first = firstUserId();
        // Never null: a null string parameter reaches PostgreSQL untyped and the driver guesses
        // bytea, which lower() has no overload for. An empty keyword makes the pattern %% instead,
        // which matches everyone — the same intent, expressed in something the database can type.
        // Spend comes along with the list rather than being fetched per row by the screen: a
        // ceiling without the current height is a number nobody can act on, and one request that
        // answers both cannot disagree with itself.
        //
        // Whose figures come back is a permission, not a display choice — an administrator does not
        // see another administrator's spending — so the row is built without them rather than the
        // screen being trusted to leave them out.
        return users.search(trimmed(request.keyword()), pageable(request))
                .map(user -> {
                    boolean visible = authz.maySeeSpendOf(user.getId(), user.getRole());
                    return UserDto.from(user, user.getId().equals(first),
                            visible && user.getMonthlyBudget() != null ? budgets.spentThisMonth(user.getId()) : null,
                            visible);
                });
    }

    @Transactional
    public UserDto create(CreateUserRequest request) {
        String name = request.name().trim();
        requireNameFree(name, null);

        User created = users.save(User.of(name, passwordEncoder.encode(request.password())));
        log.info("Created user {}", created.getId());
        return UserDto.from(created, false);
    }

    /** PUT: name and password both become what was sent. */
    @Transactional
    public UserDto replace(Long id, ReplaceUserRequest request) {
        User user = require(id);
        String name = request.name().trim();
        requireNameFree(name, id);

        user.setName(name);
        setPassword(user, request.password());
        return UserDto.from(users.save(user), isFirstUser(id));
    }

    /**
     * PATCH: only the fields that were sent, with a null meaning "leave it alone".
     * <p>
     * Not gated at the method, because there are two callers with different rights: an
     * administrator editing anybody, and a person changing their own name or password. The check is
     * therefore inside, on the one case that is not your own account.
     */
    @Transactional
    public UserDto update(Long id, PatchUserRequest request, Long callerId) {
        User user = require(id);
        if (!id.equals(callerId)) {
            requireManage(user, "change somebody else's account");
        }

        if (request.name() != null) {
            String name = request.name().trim();
            if (name.isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "A username cannot be blank", "name");
            }
            requireNameFree(name, id);
            user.setName(name);
        }

        if (request.password() != null) {
            // Changing your own password means proving you are the one sitting there. Someone who
            // found the screen unlocked must not be able to lock the owner out of their account.
            // An administrator resetting somebody else's has no such value to supply.
            if (id.equals(callerId) && (request.currentPassword() == null
                    || !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash()))) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "Your current password is wrong", "currentPassword");
            }
            setPassword(user, request.password());
        }

        return UserDto.from(users.save(user), isFirstUser(id));
    }

    /**
     * Changes what somebody may do, under four rules that are each a way an instance could otherwise
     * lock itself up or hand itself over.
     * <p>
     * <strong>SUPERADMIN cannot be given.</strong> It belongs to the seeded account — the one that
     * cannot be deleted — so it is a property of that account rather than a rank. Handing it out
     * would create a second account nobody can demote.
     * <p>
     * <strong>The seeded account cannot be changed.</strong> Demoting it would leave the instance
     * with no way to demote anybody, which is exactly the situation it exists to prevent.
     * <p>
     * <strong>Nobody changes their own role.</strong> Upwards it is self-promotion; downwards it is
     * an accident nobody else can undo.
     * <p>
     * <strong>Promotion is open to administrators, demotion is not.</strong> Any administrator may
     * hand out ADMIN; taking it back is left to the seeded account, so two administrators cannot
     * spend the afternoon demoting each other.
     */
    @Transactional
    public UserDto changeRole(Long id, Role role, Long callerId) {
        User user = require(id);

        if (role == Role.SUPERADMIN) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "There is one superadmin and it is the default account — the rank cannot be given out.",
                    "role");
        }
        if (isFirstUser(id)) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "The default account keeps its role — it is what can put things right if every "
                            + "other account is wrong.", "role");
        }
        if (id.equals(callerId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "You cannot change your own role", "role");
        }
        if (user.getRole() == role) {
            return UserDto.from(user, false);
        }

        boolean demotion = user.getRole().manages() && !role.manages();
        if (demotion && !authz.superadmin()) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "An administrator can hand out access but not take it back. Only the default "
                            + "account can, which is what stops two administrators undoing each other.",
                    "role");
        }

        user.setRole(role);
        log.info("Changed role of user {} to {}", id, role);
        return UserDto.from(users.save(user), false);
    }

    /**
     * The caller's own ceiling and what they have spent against it.
     * <p>
     * Its own call because the people who most need it are the ones who cannot reach the accounts
     * screen at all: a plain user has no business there, and telling them "you have run out"
     * without ever showing them the gauge is the kind of limit people resent rather than plan
     * around.
     */
    @Transactional(readOnly = true)
    public SpendDto mySpend(Long callerId) {
        if (callerId == null) {
            // Nobody signed in, which happens only with authentication off. There is no person for
            // a limit to be about, so there is nothing to report rather than a zero to misread.
            return SpendDto.hidden();
        }
        return users.findById(callerId)
                .map(user -> new SpendDto(
                        user.getMonthlyBudget(),
                        budgets.spentThisMonth(callerId),
                        true,
                        budgetRequests.mayAsk(callerId),
                        budgetRequests.pendingFor(callerId).isPresent(),
                        budgetRequests.undeliveredDecisionFor(callerId)
                                .map(com.ap0stole.sheetsmith.domain.dto.user.BudgetRequestDto::from)
                                .orElse(null)))
                .orElseGet(SpendDto::hidden);
    }

    /**
     * Sets or clears what somebody may spend in a calendar month.
     * <p>
     * <strong>Not your own</strong>, exactly like a role: a limit the limited person can lift is
     * not a limit. The seeded account is no exception — it is the way back into an instance, not a
     * way around its budget.
     * <p>
     * Null clears it. That is a real value here rather than a missing one, which is why this is its
     * own call and not a nullable field on the patch, where "leave it alone" already means null.
     */
    @Transactional
    public UserDto setMonthlyBudget(Long id, java.math.BigDecimal budget, Long callerId) {
        User user = require(id);

        // A strict hierarchy: your limit is set by somebody above you, never by you. The superadmin
        // is the exception because there is nobody above them — without it, a ceiling on that
        // account would be one nobody in the application could ever lift, on the very account that
        // exists to put things right.
        if (id.equals(callerId) && !authz.superadmin()) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "You cannot set your own spend limit — a limit you can lift is not a limit.",
                    "monthlyBudget");
        }
        // The other half of the same hierarchy, and the half that was missing: an administrator
        // could set one on the account above them. The superadmin starts with no ceiling and only
        // ever has the one they chose — which is the point of it on an instance somebody spun up in
        // five minutes and does not care about counting.
        if (!id.equals(callerId)) {
            // The other half of the same hierarchy. The account above you gets its own sentence,
            // because "you may not" and "that one sets its own" are different facts and the second
            // is the useful one; a peer falls to the general rule below it.
            if (user.getRole() == Role.SUPERADMIN) {
                throw new ApiException(ErrorCode.FORBIDDEN,
                        "Only the superadmin sets their own spend limit.", "monthlyBudget");
            }
            requireManage(user, "set that person's spend limit");
        }
        if (budget != null && budget.signum() < 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "A spend limit cannot be negative",
                    "monthlyBudget");
        }

        user.setMonthlyBudget(budget);
        log.info("Set monthly budget of user {} to {}", id, budget);
        User saved = users.save(user);
        // Visible by construction: the person who just set it is allowed to see it — an
        // administrator setting a limit for a user, or the superadmin for anybody.
        return UserDto.from(saved, isFirstUser(id),
                budget == null ? null : budgets.spentThisMonth(id), true);
    }

    /**
     * The same, for an act aimed at a particular person: rank matters, not just admin-ness.
     * <p>
     * {@code requireAdmin} was what stood here, and it asked only whether the caller was an
     * administrator — never who they were pointing at. An administrator could therefore reset the
     * superadmin's password and sign in as them.
     */
    private void requireManage(User target, String what) {
        if (!authz.mayManage(target.getRole())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "You do not have permission to " + what);
        }
    }

    /**
     * Removing an account is the superadmin's alone, like every other deletion here. An
     * administrator manages people — creates them, renames them, sets what they may spend — and all
     * of that can be undone by the next administrator to look at it. This cannot.
     */
    @Transactional
    public void delete(Long id, Long callerId) {
        User user = require(id);

        // Identified by being first rather than by being called "admin": the account can be
        // renamed, and a rule that a rename can switch off is not a rule.
        if (isFirstUser(id)) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "The default account cannot be deleted — it is the way back in if every other "
                            + "account is lost. Change its password instead.");
        }
        if (id.equals(callerId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "You cannot delete the account you are signed in with");
        }

        // Their way back in goes with them. Without this the person keeps a working session for as
        // long as their refresh token lives, which is up to thirty days after being removed.
        refreshTokens.revokeAllForUser(id);
        users.delete(user);
        log.info("Deleted user {}", id);
    }

    /**
     * The first row, which is the seeded administrator until somebody deletes users out from under
     * it — which they cannot, since that is the rule this answers.
     */
    private Long firstUserId() {
        return users.findFirstIdOrderById();
    }

    private boolean isFirstUser(Long id) {
        return id.equals(firstUserId());
    }

    /**
     * Sets a password and ends every session that account had.
     * <p>
     * Changing a password is what somebody does when they think the old one is known to someone
     * else. Leaving the existing sessions alive would hand the new password to the owner and take
     * nothing away from anybody already holding a token — which is the opposite of the point. It
     * signs the owner out too, on every device including this one; five seconds of inconvenience is
     * the right price for the guarantee.
     */
    private void setPassword(User user, String password) {
        user.setPasswordHash(passwordEncoder.encode(password));
        // Whatever the flag was for, it has been answered: the password is no longer the seeded one.
        user.setMustChangePassword(false);
        refreshTokens.revokeAllForUser(user.getId());
    }

    private void requireNameFree(String name, Long selfId) {
        users.findByName(name)
                .filter(existing -> !existing.getId().equals(selfId))
                .ifPresent(existing -> {
                    throw new ApiException(ErrorCode.USERNAME_TAKEN,
                            "Somebody is already called " + name, "name");
                });
    }

    private User require(Long id) {
        return users.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND, "No user with id " + id));
    }

    private Pageable pageable(UserSearchRequest request) {
        int page = request.page() == null ? 0 : Math.max(0, request.page());
        int size = request.size() == null ? 20 : Math.clamp(request.size(), 1, MAX_PAGE_SIZE);

        String field = request.sort() == null ? "name" : request.sort();
        if (!SORTABLE.contains(field)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Cannot sort users by '" + field + "'; try one of " + SORTABLE, "sort");
        }
        Sort.Direction direction = "desc".equalsIgnoreCase(request.direction())
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;

        return PageRequest.of(page, size, Sort.by(direction, field));
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
