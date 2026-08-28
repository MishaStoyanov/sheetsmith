package com.ap0stole.sheetsmith.domain.dto.price;

/**
 * One filter, matched against the provider and the model alike.
 *
 * @param keyword matched against both columns, because people remember either half
 * @param page    zero-based
 * @param size    rows per page
 */
public record PriceSearchRequest(String keyword, Integer page, Integer size) {
}
