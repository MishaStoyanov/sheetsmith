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
 */
public record UserDto(Long id, String name, boolean mustChangePassword, boolean protectedAccount,
                      Role role) {

    public static UserDto from(User user, boolean protectedAccount) {
        return new UserDto(user.getId(), user.getName(), user.isMustChangePassword(), protectedAccount,
                user.getRole());
    }
}
