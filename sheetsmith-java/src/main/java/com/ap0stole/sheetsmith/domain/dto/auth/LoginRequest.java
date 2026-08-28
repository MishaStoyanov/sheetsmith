package com.ap0stole.sheetsmith.domain.dto.auth;

import jakarta.validation.constraints.NotBlank;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @param name       the account name. A wrong one is answered exactly like a wrong password
 * @param password   sent once, over the same origin the page came from, and never stored
 * @param rememberMe thirty days instead of one, chosen once at sign-in and then carried across
 *                   every rotation — losing it on refresh would quietly demote the session
 */
public record LoginRequest(
        @NotBlank(message = "Username is required") @Schema(example = "admin") String name,
        @NotBlank(message = "Password is required") @Schema(example = "admin") String password,
        boolean rememberMe) {
}
