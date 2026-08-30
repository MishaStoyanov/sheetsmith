package com.ap0stole.sheetsmith.domain.entity;

import com.ap0stole.sheetsmith.domain.enums.BudgetRequestStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.ZoneId;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Somebody asking for a bigger monthly ceiling, and the answer they got.
 * <p>
 * A row per request rather than a flag on the account, because the interesting part is the
 * sequence: who asked, when, what was decided and by whom. A boolean would answer "is anybody
 * waiting" and lose the rest the first time it was cleared.
 */
@Entity
@Table(name = "budget_requests")
@Getter
@Setter
@NoArgsConstructor
public class BudgetRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private LocalDateTime requestedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BudgetRequestStatus status = BudgetRequestStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by")
    private User decidedBy;

    private LocalDateTime decidedAt;

    /**
     * What the limit became on approval, recorded rather than read back later.
     * <p>
     * The limit moves again afterwards — that is the point of it — so a message that said "raised
     * to $50" by looking at today's value would eventually describe something that never happened.
     */
    @Column(name = "new_limit", precision = 12, scale = 4)
    private BigDecimal newLimit;

    /** When the person was shown the outcome. Null until then, which is what makes it happen once. */
    private LocalDateTime seenAt;

    public static BudgetRequest openedBy(User user) {
        BudgetRequest request = new BudgetRequest();
        request.user = user;
        request.requestedAt = LocalDateTime.now(ZoneId.systemDefault());
        request.status = BudgetRequestStatus.PENDING;
        return request;
    }
}
