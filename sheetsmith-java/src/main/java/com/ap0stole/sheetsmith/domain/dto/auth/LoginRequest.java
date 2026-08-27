package com.ap0stole.sheetsmith.domain.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * @param rememberMe thirty days instead of one, chosen once at sign-in and then carried across
 *                   every rotation — losing it on refresh would quietly demote the session
 */
public record LoginRequest(
        @NotBlank(message = "Username is required") String name,
        @NotBlank(message = "Password is required") String password,
        boolean rememberMe) {
}
