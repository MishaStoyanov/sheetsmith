package com.ap0stole.sheetsmith.domain.dto.user;

import com.ap0stole.sheetsmith.domain.entity.User;

/**
 * A user, as everyone else may see them. There is deliberately no password field of any kind — not
 * even the hash: a DTO that can carry one is a DTO that will eventually be logged.
 *
 * @param protectedAccount whether this is the first user, who cannot be deleted
 */
public record UserDto(Long id, String name, boolean mustChangePassword, boolean protectedAccount) {

    public static UserDto from(User user, boolean protectedAccount) {
        return new UserDto(user.getId(), user.getName(), user.isMustChangePassword(), protectedAccount);
    }
}
