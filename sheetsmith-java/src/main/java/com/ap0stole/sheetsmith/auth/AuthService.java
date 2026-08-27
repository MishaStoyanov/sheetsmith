package com.ap0stole.sheetsmith.auth;

import com.ap0stole.sheetsmith.configs.AuthConfig;
import com.ap0stole.sheetsmith.domain.dto.auth.AuthResponse;
import com.ap0stole.sheetsmith.domain.dto.auth.LoginRequest;
import com.ap0stole.sheetsmith.domain.dto.auth.MeDto;
import com.ap0stole.sheetsmith.domain.entity.User;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import com.ap0stole.sheetsmith.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Signing in, refreshing and signing out, with the token machinery kept behind it. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthConfig authConfig;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokens;
    private final RefreshTokenService refreshTokens;

    /** The response, plus the refresh token the controller has to put in a cookie. */
    public record SignedIn(AuthResponse response, RefreshTokenService.IssuedToken refreshToken) {}

    public SignedIn login(LoginRequest request) {
        User user = users.findByName(request.name())
                .filter(candidate -> passwordEncoder.matches(request.password(), candidate.getPasswordHash()))
                // One message for both "no such user" and "wrong password", and the password is
                // checked even when the name is unknown would be the other half of that — here the
                // filter keeps the two paths textually identical, which is what matters to a caller
                // trying to find out whether a name exists.
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "Wrong username or password"));

        log.info("User {} signed in", user.getName());
        return issue(user, request.rememberMe());
    }

    public SignedIn refresh(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Not signed in");
        }
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(presentedToken);
        return new SignedIn(response(rotation.user(), rotation.token()), rotation.token());
    }

    public void logout(String presentedToken) {
        if (presentedToken != null && !presentedToken.isBlank()) {
            refreshTokens.revoke(presentedToken);
        }
    }

    public MeDto me(Long userId) {
        return users.findById(userId)
                .map(MeDto::from)
                // The account was deleted while its token was still valid — which is exactly why
                // deleting someone also withdraws their refresh tokens.
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "This account no longer exists"));
    }

    private SignedIn issue(User user, boolean rememberMe) {
        RefreshTokenService.IssuedToken refreshToken = refreshTokens.issue(user, rememberMe);
        return new SignedIn(response(user, refreshToken), refreshToken);
    }

    private AuthResponse response(User user, RefreshTokenService.IssuedToken refreshToken) {
        return new AuthResponse(
                accessTokens.issue(user),
                authConfig.getAccessTokenTtl().toSeconds(),
                MeDto.from(user));
    }
}
