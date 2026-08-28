package com.ap0stole.sheetsmith.domain.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Asks the assistant what it would improve — no instruction, that is the point.
 *
 * @param sessionId the document to look at
 */
public record SuggestRequest(@NotBlank String sessionId) {
}
