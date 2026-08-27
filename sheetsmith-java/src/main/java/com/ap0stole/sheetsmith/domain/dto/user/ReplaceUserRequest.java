package com.ap0stole.sheetsmith.domain.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * PUT: the account as it should be from now on. Both fields are required precisely because this is
 * a replacement — an omitted password here would mean "keep the old one", which is a patch wearing
 * a put's name.
 */
public record ReplaceUserRequest(
        @NotBlank(message = "A username is required")
        @Size(max = 255, message = "That username is too long")
        String name,

        @NotBlank(message = "A password is required")
        @Size(min = 4, message = "A password needs at least 4 characters")
        String password) {
}
