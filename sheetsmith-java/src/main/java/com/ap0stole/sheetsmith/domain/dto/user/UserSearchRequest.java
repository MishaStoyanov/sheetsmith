package com.ap0stole.sheetsmith.domain.dto.user;

/**
 * @param keyword  matched against the username, case-insensitively; null or blank means everyone
 * @param sort     one of the fields the service allows, not an arbitrary property path
 */
public record UserSearchRequest(String keyword, Integer page, Integer size, String sort, String direction) {
}
