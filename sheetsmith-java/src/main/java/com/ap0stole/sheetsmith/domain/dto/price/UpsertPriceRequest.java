package com.ap0stole.sheetsmith.domain.dto.price;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * PUT: the price for this model from now on. The key is natural — provider plus model — so this
 * doubles as "add a model nobody has priced yet", which is what PUT is for.
 *
 * @param provider          the vendor, as the audit records it
 * @param model             the model name exactly as the provider answers with it — an inexact
 *                          name prices nothing, and nothing says so
 * @param inputPerMillion   what a million prompt tokens cost
 * @param outputPerMillion  what a million answer tokens cost
 */
public record UpsertPriceRequest(
        @NotBlank(message = "A provider is required") @Schema(example = "OPENAI") String provider,
        @NotBlank(message = "A model is required") @Schema(example = "gpt-4o") String model,

        @NotNull(message = "An input price is required")
        @DecimalMin(value = "0.0", message = "A price cannot be negative")
        @Schema(example = "2.50") BigDecimal inputPerMillion,

        @NotNull(message = "An output price is required")
        @DecimalMin(value = "0.0", message = "A price cannot be negative")
        @Schema(example = "10.00") BigDecimal outputPerMillion) {
}
