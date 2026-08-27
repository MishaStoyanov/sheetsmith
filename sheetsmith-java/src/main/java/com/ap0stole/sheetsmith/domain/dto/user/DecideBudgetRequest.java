package com.ap0stole.sheetsmith.domain.dto.user;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * The answer to somebody's request.
 *
 * @param newLimit required when approving and ignored when declining, because approving is what
 *                 raises the limit — there is no such thing here as an approval that changes
 *                 nothing
 */
public record DecideBudgetRequest(
        @NotNull(message = "Say whether this is approved") Boolean approve,

        @DecimalMin(value = "0.0", message = "A spend limit cannot be negative")
        BigDecimal newLimit) {
}
