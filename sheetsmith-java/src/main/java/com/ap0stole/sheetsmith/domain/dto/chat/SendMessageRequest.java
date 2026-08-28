package com.ap0stole.sheetsmith.domain.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One turn of the conversation.
 *
 * @param text what to ask. A question is answered from the sheet; an instruction changes it and
 *             commits a revision, and either way the reply carries the steps it took
 */
public record SendMessageRequest(
        @Schema(example = "Which product sold the most last quarter?")
        @NotBlank @Size(max = 2000) String text
) {
}
