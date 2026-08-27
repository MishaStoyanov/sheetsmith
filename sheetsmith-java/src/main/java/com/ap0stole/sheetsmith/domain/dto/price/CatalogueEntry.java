package com.ap0stole.sheetsmith.domain.dto.price;

import java.math.BigDecimal;

/**
 * One model as an outside catalogue lists it, already converted to this application's units.
 *
 * @param provider as this instance records providers — OPENAI, ANTHROPIC, GEMINI
 * @param model    the catalogue's name for it, which is not always what a run records: see the
 *                 matching rules in the catalogue service
 */
public record CatalogueEntry(String provider, String model,
                             BigDecimal inputPerMillion, BigDecimal outputPerMillion) {
}
