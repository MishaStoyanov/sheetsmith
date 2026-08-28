package com.ap0stole.sheetsmith.domain.dto.price;

import com.ap0stole.sheetsmith.domain.entity.ModelPrice;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * @param id              the row's own id, for PATCH and DELETE
 * @param provider        the vendor, as the audit records it: OPENAI, ANTHROPIC, OLLAMA…
 * @param model           the model name exactly as the provider answers with it
 * @param inputPerMillion what a million prompt tokens cost, in the instance's currency
 * @param outputPerMillion what a million answer tokens cost
 * @param updatedAt       when this price was last **confirmed** — checking it against the
 *                        catalogue and finding no change counts, which is what makes the age
 *                        readable as "somebody looked"
 * @param usedByCalls how many recorded calls this price applies to. Carried so the screen can warn
 *                    before a delete rather than after — and so the warning is the same number the
 *                    server would refuse with.
 */
public record ModelPriceDto(Long id, String provider, String model,
                            BigDecimal inputPerMillion, BigDecimal outputPerMillion,
                            LocalDateTime updatedAt, long usedByCalls) {

    public static ModelPriceDto from(ModelPrice price, long usedByCalls) {
        return new ModelPriceDto(price.getId(), price.getProvider(), price.getModel(),
                price.getInputPerMillion(), price.getOutputPerMillion(), price.getUpdatedAt(), usedByCalls);
    }
}
