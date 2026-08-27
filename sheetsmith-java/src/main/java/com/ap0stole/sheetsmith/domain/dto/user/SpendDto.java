package com.ap0stole.sheetsmith.domain.dto.user;

import java.math.BigDecimal;

/**
 * What somebody may spend this month and what they have spent.
 *
 * @param monthlyBudget  the ceiling, or null where there is none
 * @param spentThisMonth what has gone, as far as prices can tell — always answered, because "no
 *                       limit" is not the same as "nothing spent" and the second is worth knowing
 *                       on its own
 * @param visible        whether the caller is allowed to see this person's figures at all. False
 *                       carries nulls rather than an error: a screen listing people should be able
 *                       to show a row without the column, not fail to draw the row
 */
public record SpendDto(BigDecimal monthlyBudget, BigDecimal spentThisMonth, boolean visible) {

    public static SpendDto hidden() {
        return new SpendDto(null, null, false);
    }
}
