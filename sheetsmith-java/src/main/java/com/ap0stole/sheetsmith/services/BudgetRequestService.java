package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.auth.Authz;
import com.ap0stole.sheetsmith.domain.entity.BudgetRequest;
import com.ap0stole.sheetsmith.domain.entity.User;
import com.ap0stole.sheetsmith.domain.enums.BudgetRequestStatus;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import com.ap0stole.sheetsmith.repository.BudgetRequestRepository;
import com.ap0stole.sheetsmith.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Asking for a bigger ceiling, and answering.
 * <p>
 * The shape of this is one decision: <strong>approving means the limit goes up</strong>. An
 * approval that left the figure alone would tell somebody their limit had been increased while
 * nothing had changed, and a notification that lies once is a notification nobody reads again. So
 * approving carries the new number and is refused if it is not larger.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetRequestService {

    /**
     * How much of the limit has to be gone before asking makes sense.
     * <p>
     * The last fifteen per cent: early enough that the answer can arrive before the work stops,
     * late enough that the button is not sitting there all month inviting a click. Below it there
     * is nothing to ask about, and the button is absent rather than disabled — a control that
     * refuses is a control people learn to ignore.
     */
    private static final BigDecimal ASK_FROM = new BigDecimal("0.85");

    private final BudgetRequestRepository requests;
    private final UserRepository users;
    private final BudgetService budgets;
    private final Authz authz;

    /** Where "now" comes from, so a test can decide what it is. */
    private final Clock clock;

    /**
     * Whether this person is close enough to their ceiling for asking to mean anything.
     * <p>
     * The three pairs below are each an annotated entry point over a plain body. Callers in other
     * beans arrive through the proxy and get their transaction; {@link #ask} and {@link #markSeen}
     * call the body directly, because a call through {@code this} never reaches the proxy and an
     * annotation that does nothing where it is written is one the next reader will believe.
     */
    @Transactional(readOnly = true)
    public boolean mayAsk(Long userId) {
        return withinAskingDistance(userId);
    }

    private boolean withinAskingDistance(Long userId) {
        if (userId == null) {
            return false;
        }
        BigDecimal limit = users.findById(userId).map(User::getMonthlyBudget).orElse(null);
        if (limit == null || limit.signum() <= 0) {
            // No ceiling, or one of zero that no amount of asking turns into headroom.
            return false;
        }
        if (authz.superadmin()) {
            // Nothing to ask, and nobody to ask: the superadmin sets their own limit, and a request
            // from them could never be answered by anyone.
            return false;
        }
        return budgets.spentThisMonth(userId).compareTo(limit.multiply(ASK_FROM)) >= 0;
    }

    @Transactional(readOnly = true)
    public Optional<BudgetRequest> pendingFor(Long userId) {
        return pending(userId);
    }

    private Optional<BudgetRequest> pending(Long userId) {
        return userId == null
                ? Optional.empty()
                : requests.findFirstByUserIdAndStatus(userId, BudgetRequestStatus.PENDING);
    }

    /** The decision this person has not been shown yet, if there is one. */
    @Transactional(readOnly = true)
    public Optional<BudgetRequest> undeliveredDecisionFor(Long userId) {
        return undeliveredDecision(userId);
    }

    private Optional<BudgetRequest> undeliveredDecision(Long userId) {
        return userId == null
                ? Optional.empty()
                : requests.findFirstByUserIdAndStatusInAndSeenAtIsNullOrderByDecidedAtDesc(
                        userId, List.of(BudgetRequestStatus.APPROVED, BudgetRequestStatus.DECLINED));
    }

    @Transactional
    public BudgetRequest ask(Long callerId) {
        if (callerId == null) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "There is nobody to raise a limit for on an instance without accounts.");
        }
        User user = users.findById(callerId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND, "No such account"));

        if (user.getMonthlyBudget() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "You have no spend limit to raise.");
        }
        if (!withinAskingDistance(callerId)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "You still have room this month. Ask when you are closer to your limit.");
        }
        // The database has a partial unique index saying the same thing, because check-then-insert
        // is two statements and two clicks is not a hypothetical.
        if (pending(callerId).isPresent()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "You already have a request waiting. One at a time.");
        }

        log.info("User {} asked for a higher spend limit", callerId);
        return requests.save(BudgetRequest.openedBy(user));
    }

    /**
     * Everything waiting, narrowed to the people this caller is allowed to see.
     * <p>
     * Filtered here rather than on the screen: an administrator has no business reading that a peer
     * is running out of money, and a list that arrived complete and was trimmed in the browser
     * would have already told them.
     */
    @Transactional(readOnly = true)
    public List<com.ap0stole.sheetsmith.domain.dto.user.BudgetRequestDto> pendingVisibleTo() {
        return requests.findByStatus(BudgetRequestStatus.PENDING).stream()
                .filter(request -> authz.maySeeSpendOf(request.getUser().getId(), request.getUser().getRole()))
                .map(com.ap0stole.sheetsmith.domain.dto.user.BudgetRequestDto::from)
                .toList();
    }

    /**
     * Answers somebody's request.
     * <p>
     * Who may answer is the same question as who may see the money: an administrator for the
     * ordinary users they look after, the superadmin for anybody. Never your own, exactly as with
     * setting a limit — approving your own request is setting your own limit with extra steps.
     */
    @Transactional
    public BudgetRequest decide(Long requestId, boolean approve, BigDecimal newLimit, Long callerId) {
        BudgetRequest request = requests.findById(requestId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND, "No such request"));

        if (request.getStatus() != BudgetRequestStatus.PENDING) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "That request has already been answered.");
        }

        User target = request.getUser();
        if (!authz.maySeeSpendOf(target.getId(), target.getRole())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "You cannot answer that request.");
        }
        if (target.getId().equals(callerId)) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "You cannot answer your own request — that is setting your own limit with extra steps.");
        }

        if (approve) {
            BigDecimal current = target.getMonthlyBudget();
            if (newLimit == null || (current != null && newLimit.compareTo(current) <= 0)) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "Approving raises the limit, so the new one has to be higher than $%s. "
                                .formatted(current == null ? "0" : current.toPlainString())
                                + "Decline it instead if it should stay where it is.", "newLimit");
            }
            target.setMonthlyBudget(newLimit);
            users.save(target);
            request.setNewLimit(newLimit);
        }

        request.setStatus(approve ? BudgetRequestStatus.APPROVED : BudgetRequestStatus.DECLINED);
        request.setDecidedAt(LocalDateTime.now(clock));
        request.setDecidedBy(callerId == null ? null : users.findById(callerId).orElse(null));

        log.info("Request {} for user {} was {}", requestId, target.getId(), request.getStatus());
        return requests.save(request);
    }

    /**
     * Marks the outcome as read, which is what makes the notification happen once.
     * <p>
     * Only your own, and silently ignored where there is nothing waiting: dismissing a message that
     * has already gone is not an error worth showing somebody.
     */
    @Transactional
    public void markSeen(Long callerId) {
        undeliveredDecision(callerId).ifPresent(request -> {
            request.setSeenAt(LocalDateTime.now(clock));
            requests.save(request);
        });
    }
}
