package com.ap0stole.sheetsmith.domain.dto.auth;

import com.ap0stole.sheetsmith.domain.entity.User;
import com.ap0stole.sheetsmith.domain.enums.Role;

/**
 * Who the caller is.
 * <p>
 * {@code mustChangePassword} reaches the UI here rather than through {@code /api/capabilities}:
 * that endpoint answers strangers, and "this instance still has its default password" is exactly
 * the sentence not to hand one. Told to the person who has signed in, it is advice.
 *
 * @param id                 the account's own id, which is what a token names
 * @param name               what they are called, and what they sign in with
 * @param mustChangePassword still on the password the migration seeded. The interface nags until
 *                           it is false
 * @param role               USER, ADMIN or SUPERADMIN — what they may do to other accounts
 * @param monthlyBudget      their own ceiling in the instance's currency, or null for none
 */
public record MeDto(Long id, String name, boolean mustChangePassword, Role role,
                    java.math.BigDecimal monthlyBudget) {

    public static MeDto from(User user) {
        return new MeDto(user.getId(), user.getName(), user.isMustChangePassword(), user.getRole(),
                user.getMonthlyBudget());
    }
}
