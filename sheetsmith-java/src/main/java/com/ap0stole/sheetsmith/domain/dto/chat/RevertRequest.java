package com.ap0stole.sheetsmith.domain.dto.chat;

import jakarta.validation.constraints.Min;

public record RevertRequest(
        @Min(0) int revision
) {
}
