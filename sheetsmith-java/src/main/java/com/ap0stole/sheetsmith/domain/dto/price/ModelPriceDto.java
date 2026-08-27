package com.ap0stole.sheetsmith.domain.dto.price;

import com.ap0stole.sheetsmith.domain.entity.ModelPrice;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
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
