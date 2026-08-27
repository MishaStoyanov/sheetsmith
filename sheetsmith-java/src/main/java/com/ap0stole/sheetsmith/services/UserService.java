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
import org.springframework.security.access.prepost.PreAuthorize;
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

    /**
     * Deliberately open to anyone signed in, unlike everything else here.
     * <p>
     * The history screen fills its "started by" filter from this list, so locking it to
     * administrators would quietly empty a filter ordinary people use — and the names are not a
     * secret anyway: the analytics screen prints them beside what each person spent.
     */
    @PreAuthorize("@authz.signedIn()")
    public Page<UserDto> search(UserSearchRequest request) {
        Long first = firstUserId();
        // Never null: a null string parameter reaches PostgreSQL untyped and the driver guesses
        // bytea, which lower() has no overload for. An empty keyword makes the pattern %% instead,
        // which matches everyone — the same intent, expressed in something the database can type.
        return users.search(trimmed(request.keyword()), pageable(request))
                .map(user -> UserDto.from(user, user.getId().equals(first)));
    }

    @PreAuthorize("@authz.admin()")
    @Transactional
    public UserDto create(CreateUserRequest request) {
        String name = request.name().trim();
        requireNameFree(name, null);

        User created = users.save(User.of(name, passwordEncoder.encode(request.password())));
        log.info("Created user {}", created.getId());
        return UserDto.from(created, false);
    }

    /** PUT: name and password both become what was sent. */
    @PreAuthorize("@authz.admin()")
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
            requireAdmin("change somebody else's account");
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
            if (id.equals(callerId)) {
                if (request.currentPassword() == null
                        || !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
                    throw new ApiException(ErrorCode.VALIDATION_ERROR,
                            "Your current password is wrong", "currentPassword");
                }
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
    @PreAuthorize("@authz.admin()")
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

    /** The half of a check that could not live on the method, because the other caller is yourself. */
    private void requireAdmin(String what) {
        if (!authz.admin()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "You do not have permission to " + what);
        }
    }

    @PreAuthorize("@authz.admin()")
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
