package com.ap0stole.sheetsmith.controller;

import com.ap0stole.sheetsmith.auth.AuthService;
import com.ap0stole.sheetsmith.auth.CurrentUser;
import com.ap0stole.sheetsmith.auth.RefreshTokenService;
import com.ap0stole.sheetsmith.configs.AuthConfig;
import com.ap0stole.sheetsmith.domain.dto.auth.AuthResponse;
import com.ap0stole.sheetsmith.domain.dto.auth.LoginRequest;
import com.ap0stole.sheetsmith.domain.dto.auth.MeDto;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;


/**
 * Signing in and staying signed in.
 * <p>
 * The refresh token is never in a response body. It is an httpOnly cookie, scoped to this path and
 * {@code SameSite=Strict}: a thirty-day credential that no script on the page can read, and that
 * the browser will not attach to a request another site started. The short-lived access token goes
 * in the body instead, for the page to keep in memory.
 */
@Tag(name = "Auth", description = "Signing in, refreshing, signing out - present only when accounts are switched on.")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    static final String REFRESH_COOKIE = "sheetsmith_refresh";
    private static final String COOKIE_PATH = "/api/auth";

    private final AuthConfig authConfig;
    private final AuthService authService;
    private final CurrentUser currentUser;

    @Operation(summary = "Sign in",
            description = "Returns an access token in the body; the refresh token is an httpOnly SameSite=Strict cookie scoped to /api/auth. A wrong name and a wrong password are answered identically.")
    @ApiResponse(responseCode = "401", description = "Wrong name, or wrong password. The two are answered identically: which half was right is not information a stranger should be able to collect.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody @Valid LoginRequest request,
                                              HttpServletResponse response) {
        requireAuthEnabled();
        return answer(authService.login(request), response);
    }

    @Operation(summary = "Exchange the refresh cookie for a new access token",
            description = "The cookie is rotated on every use: the old one stops working, and presenting a used one ends every session of that account, because it means a copy is in circulation.")
    @ApiResponse(responseCode = "401", description = "No refresh cookie, or one already used. A used token means a copy is in circulation, so every session of that account ends.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletResponse response) {
        requireAuthEnabled();
        return answer(authService.refresh(refreshToken), response);
    }

    @Operation(summary = "Sign out",
            description = "Ends this session and clears the cookie. Answers cleanly when there is nothing to end.")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletResponse response) {
        requireAuthEnabled();
        authService.logout(refreshToken);
        // Cleared with the same attributes it was set with, or the browser keeps the old one
        // alongside and the next refresh presents a token this instance has already withdrawn.
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@authz.signedIn()")
    @Operation(summary = "Who the caller is",
            description = "Name, role, and whether the default password still needs changing.")
    @GetMapping("/me")
    public ResponseEntity<MeDto> me() {
        requireAuthEnabled();
        Long id = currentUser.id()
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "Not signed in"));
        return ResponseEntity.ok(authService.me(id));
    }

    private ResponseEntity<AuthResponse> answer(AuthService.SignedIn signedIn, HttpServletResponse response) {
        RefreshTokenService.IssuedToken token = signedIn.refreshToken();
        Duration life = Duration.between(LocalDateTime.now(), token.expiresAt());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(token.value(), life).toString());
        return ResponseEntity.ok(signedIn.response());
    }

    private ResponseCookie cookie(String value, Duration life) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .sameSite("Strict")
                // Secure would be correct on the public internet and wrong here: this app is
                // normally reached over plain http on localhost, where a secure cookie is simply
                // never sent and the session silently fails to persist. SameSite=Strict is what
                // carries the weight.
                .secure(false)
                .path(COOKIE_PATH)
                .maxAge(life.isNegative() ? Duration.ZERO : life)
                .build();
    }

    /**
     * With authentication off these endpoints answer nothing at all. Letting them issue tokens on
     * an instance that does not check them would hand out credentials that mean nothing.
     */
    private void requireAuthEnabled() {
        if (!authConfig.isEnabled()) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "This instance runs without authentication. Set SHEETSMITH_AUTH_ENABLED=true to use accounts.");
        }
    }
}
