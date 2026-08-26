package com.ap0stole.sheetsmith.domain.dto;

import jakarta.validation.constraints.NotBlank;

/** Asks the assistant what it would improve — no instruction, that is the point. */
public record SuggestRequest(@NotBlank String sessionId) {
}
