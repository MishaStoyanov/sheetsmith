package com.ap0stole.sheetsmith.domain.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A new account.
 *
 * @param name     unique across the instance — "who ran this" stops being an answer at the second
 *                 namesake
 * @param password what they will sign in with. They can change it themselves afterwards, and will
 *                 need the current one to do it
 */
public record CreateUserRequest(
        @NotBlank(message = "A username is required")
        @Size(max = 255, message = "That username is too long")
        @Schema(example = "dana") String name,

        @NotBlank(message = "A password is required")
        @Size(min = 4, message = "A password needs at least 4 characters")
        @Schema(example = "a-good-password") String password) {
}
