package com.ap0stole.sheetsmith.domain.dto.user;

import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

/**
 * A monthly spend limit, or null for none.
 * <p>
 * Null is a real value here — "this person has no ceiling" — which is why this is a PUT of its own
 * rather than a field on the patch, where null already means "do not touch this".
 *
 * @param monthlyBudget the ceiling for a calendar month, or null to lift it. Only what has a
 *                      price counts against it: a local model bills nothing, and an unpriced one
 *                      cannot be measured, so neither reaches the limit
 */
public record SetBudgetRequest(
        @DecimalMin(value = "0.0", message = "A spend limit cannot be negative")
        BigDecimal monthlyBudget) {
}
