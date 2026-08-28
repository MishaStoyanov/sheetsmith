package com.ap0stole.sheetsmith.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Asks for a plan against a session's current revision. There is no file here on purpose: the
 * session's revision chain is the single home of the sheet both flows work on.
 *
 * @param sessionId   the document to plan against, from POST /api/chat/sessions
 * @param instruction what to do, in plain language. The plan comes back as steps to review; this
 *                    call writes nothing
 */
public record PlanRequest(
        @Schema(example = "0f8b2c1e-4c6a-4d9f-9d2f-2f0f9a1c7b31") @NotBlank String sessionId,
        @Schema(example = "Bold the header row and format column C as currency")
        @NotBlank @Size(max = 2000) String instruction
) {
}
