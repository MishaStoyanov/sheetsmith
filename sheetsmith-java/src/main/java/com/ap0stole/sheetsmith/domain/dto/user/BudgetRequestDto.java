package com.ap0stole.sheetsmith.domain.dto.user;

import com.ap0stole.sheetsmith.domain.entity.BudgetRequest;
import com.ap0stole.sheetsmith.domain.enums.BudgetRequestStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One request for a bigger ceiling.
 *
 * @param newLimit what the limit became — present on an approval, null otherwise. Read from the
 *                 request rather than from the account, so a message about a decision says what
 *                 that decision did rather than what is true today
 */
public record BudgetRequestDto(Long id, Long userId, String userName, BudgetRequestStatus status,
                               LocalDateTime requestedAt, LocalDateTime decidedAt,
                               BigDecimal newLimit) {

    public static BudgetRequestDto from(BudgetRequest request) {
        return new BudgetRequestDto(request.getId(), request.getUser().getId(),
                request.getUser().getName(), request.getStatus(),
                request.getRequestedAt(), request.getDecidedAt(), request.getNewLimit());
    }
}
