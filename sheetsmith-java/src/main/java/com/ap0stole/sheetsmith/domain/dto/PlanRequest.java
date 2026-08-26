package com.ap0stole.sheetsmith.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Asks for a plan against a session's current revision. There is no file here on purpose: the
 * session's revision chain is the single home of the sheet both flows work on.
 */
public record PlanRequest(
        @NotBlank String sessionId,
        @NotBlank @Size(max = 2000) String instruction
) {
}
