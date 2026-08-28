package com.ap0stole.sheetsmith.domain.dto.user;

import com.ap0stole.sheetsmith.domain.entity.BudgetRequest;
import com.ap0stole.sheetsmith.domain.enums.BudgetRequestStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One request for a bigger ceiling.
 *
 * @param id          the request's own id, which is what the decision endpoint takes
 * @param userId      who asked
 * @param userName    their name, so a queue can be read without a second call
 * @param status      PENDING, APPROVED or DECLINED. A decline is still an answer, and is still
 *                    delivered: a request that vanished teaches people the button does nothing
 * @param requestedAt when they asked
 * @param decidedAt   when it was answered, or null while it waits
 * @param newLimit    what the limit became — present on an approval, null otherwise. Read from the
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
