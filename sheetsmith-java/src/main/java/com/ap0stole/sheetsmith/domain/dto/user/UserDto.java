package com.ap0stole.sheetsmith.domain.dto.user;

import com.ap0stole.sheetsmith.domain.entity.User;
import com.ap0stole.sheetsmith.domain.enums.Role;

import java.math.BigDecimal;

/**
 * A user, as everyone else may see them. There is deliberately no password field of any kind — not
 * even the hash: a DTO that can carry one is a DTO that will eventually be logged.
 *
 * @param protectedAccount whether this is the first user, who cannot be deleted
 * @param role             what they may do to other accounts. Readable by anyone who can read the
 *                         list at all: it explains why a button is missing, and hiding it would only
 *                         make the interface look arbitrary
 * @param monthlyBudget    what they may spend in a calendar month, or null for no limit — and also
 *                         null where the caller may not see it, which is why it travels with the
 *                         flag below rather than alone
 * @param spentThisMonth   what they have spent so far, as far as prices can tell
 * @param spendVisible     whether the caller is allowed to see this person's money at all. A
 *                         separate flag because null already means "no limit", and a screen has to
 *                         tell "unlimited" from "not your business"
 */
public record UserDto(Long id, String name, boolean mustChangePassword, boolean protectedAccount,
                      Role role, BigDecimal monthlyBudget, BigDecimal spentThisMonth,
                      boolean spendVisible) {

    public static UserDto from(User user, boolean protectedAccount) {
        return from(user, protectedAccount, null, true);
    }

    public static UserDto from(User user, boolean protectedAccount,
                               BigDecimal spentThisMonth, boolean spendVisible) {
        return new UserDto(user.getId(), user.getName(), user.isMustChangePassword(), protectedAccount,
                user.getRole(),
                // The ceiling is hidden with the spending, or a reader works out one from the other.
                spendVisible ? user.getMonthlyBudget() : null,
                spentThisMonth, spendVisible);
    }
}
