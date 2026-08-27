package com.ap0stole.sheetsmith.auth;

/**
 * The caller, as the access token described them. The id is what everything downstream uses; the
 * name is carried only so the UI can say hello without another round trip.
 */
public record AuthenticatedUser(Long id, String name) {
}
