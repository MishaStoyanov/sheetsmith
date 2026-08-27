package com.ap0stole.sheetsmith.domain.dto.user;

import jakarta.validation.constraints.Size;

/**
 * PATCH: only what is being changed. Null means "leave it alone", which is why nothing here is
 * required and why blank is rejected separately from absent.
 *
 * @param currentPassword required when changing your own password, and only then. Someone who
 *                        walked up to an unlocked screen should not be able to lock the owner out;
 *                        an administrator resetting someone else's password has no such value to
 *                        supply and is not asked for one.
 */
public record PatchUserRequest(
        @Size(max = 255, message = "That username is too long") String name,
        @Size(min = 4, message = "A password needs at least 4 characters") String password,
        String currentPassword) {
}
