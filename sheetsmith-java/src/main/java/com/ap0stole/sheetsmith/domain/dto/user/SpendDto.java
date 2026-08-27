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
 * @param mayAsk         far enough through the limit for asking for more to mean something
 * @param pending        already waiting on an answer, so the button says so instead of offering a
 *                       second request
 * @param decision       an answer this person has not been shown yet, or null. It is delivered on
 *                       whatever screen they are on and marked read once, rather than waiting for
 *                       them to visit somewhere
 */
public record SpendDto(BigDecimal monthlyBudget, BigDecimal spentThisMonth, boolean visible,
                       boolean mayAsk, boolean pending, BudgetRequestDto decision) {

    public static SpendDto hidden() {
        return new SpendDto(null, null, false, false, false, null);
    }
}
