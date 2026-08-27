package com.ap0stole.sheetsmith.domain.dto.price;

import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

/** PATCH: one of the two prices, usually. Null means leave it as it is. */
public record PatchPriceRequest(
        @DecimalMin(value = "0.0", message = "A price cannot be negative") BigDecimal inputPerMillion,
        @DecimalMin(value = "0.0", message = "A price cannot be negative") BigDecimal outputPerMillion) {
}
