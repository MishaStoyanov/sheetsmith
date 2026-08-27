package com.ap0stole.sheetsmith.domain.dto.auth;

/**
 * What a sign-in or a refresh hands back.
 * <p>
 * The refresh token is <em>not</em> here: it travels as an httpOnly cookie, which is the whole
 * point of that choice — a thirty-day credential no script on the page can read.
 *
 * @param expiresInSeconds so the browser can renew a minute early rather than discovering the
 *                         expiry by being refused
 */
public record AuthResponse(String accessToken, long expiresInSeconds, MeDto user) {
}
