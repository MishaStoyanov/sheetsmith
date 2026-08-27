package com.ap0stole.sheetsmith.domain.dto.price;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * PUT: the price for this model from now on. The key is natural — provider plus model — so this
 * doubles as "add a model nobody has priced yet", which is what PUT is for.
 */
public record UpsertPriceRequest(
        @NotBlank(message = "A provider is required") String provider,
        @NotBlank(message = "A model is required") String model,

        @NotNull(message = "An input price is required")
        @DecimalMin(value = "0.0", message = "A price cannot be negative")
        BigDecimal inputPerMillion,

        @NotNull(message = "An output price is required")
        @DecimalMin(value = "0.0", message = "A price cannot be negative")
        BigDecimal outputPerMillion) {
}
