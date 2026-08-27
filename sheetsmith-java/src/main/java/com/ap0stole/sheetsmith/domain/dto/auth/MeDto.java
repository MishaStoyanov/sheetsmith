package com.ap0stole.sheetsmith.domain.dto.auth;

import com.ap0stole.sheetsmith.domain.entity.User;

/**
 * Who the caller is.
 * <p>
 * {@code mustChangePassword} reaches the UI here rather than through {@code /api/capabilities}:
 * that endpoint answers strangers, and "this instance still has its default password" is exactly
 * the sentence not to hand one. Told to the person who has signed in, it is advice.
 */
public record MeDto(Long id, String name, boolean mustChangePassword) {

    public static MeDto from(User user) {
        return new MeDto(user.getId(), user.getName(), user.isMustChangePassword());
    }
}
