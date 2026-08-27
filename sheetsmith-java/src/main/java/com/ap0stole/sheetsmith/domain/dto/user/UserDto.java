package com.ap0stole.sheetsmith.domain.dto.user;

import com.ap0stole.sheetsmith.domain.entity.User;
import com.ap0stole.sheetsmith.domain.enums.Role;

/**
 * A user, as everyone else may see them. There is deliberately no password field of any kind — not
 * even the hash: a DTO that can carry one is a DTO that will eventually be logged.
 *
 * @param protectedAccount whether this is the first user, who cannot be deleted
 * @param role             what they may do to other accounts. Readable by anyone who can read the
 *                         list at all: it explains why a button is missing, and hiding it would only
 *                         make the interface look arbitrary
 * @param monthlyBudget    what they may spend in a calendar month, or null for no limit
 * @param spentThisMonth   what they have spent so far, as far as prices can tell — carried beside
 *                         the limit because a ceiling without the current height is a number nobody
 *                         can act on. Null where it was not asked for
 */
public record UserDto(Long id, String name, boolean mustChangePassword, boolean protectedAccount,
                      Role role, java.math.BigDecimal monthlyBudget,
                      java.math.BigDecimal spentThisMonth) {

    public static UserDto from(User user, boolean protectedAccount) {
        return from(user, protectedAccount, null);
    }

    public static UserDto from(User user, boolean protectedAccount, java.math.BigDecimal spentThisMonth) {
        return new UserDto(user.getId(), user.getName(), user.isMustChangePassword(), protectedAccount,
                user.getRole(), user.getMonthlyBudget(), spentThisMonth);
    }
}
