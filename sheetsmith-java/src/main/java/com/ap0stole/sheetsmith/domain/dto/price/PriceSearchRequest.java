package com.ap0stole.sheetsmith.domain.dto.price;

/** One filter, matched against the provider and the model alike. */
public record PriceSearchRequest(String keyword, Integer page, Integer size) {
}
