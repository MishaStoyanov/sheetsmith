package com.ap0stole.sheetsmith.domain.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank(message = "A username is required")
        @Size(max = 255, message = "That username is too long")
        String name,

        @NotBlank(message = "A password is required")
        @Size(min = 4, message = "A password needs at least 4 characters")
        String password) {
}
