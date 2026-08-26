package com.ap0stole.sheetsmith.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "action_results")
@Getter
@Setter
@NoArgsConstructor
public class ActionResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private JobRecord job;

    @Column(nullable = false)
    private int executionOrder;

    @Column(nullable = false)
    private String actionType;

    /** Plain-language rendering of the step, so history reads as sentences rather than enums. */
    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private boolean success;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    public static ActionResult success(JobRecord job, String actionType, int order, String description) {
        ActionResult r = new ActionResult();
        r.job = job;
        r.actionType = actionType;
        r.description = description;
        r.executionOrder = order;
        r.success = true;
        return r;
    }

    public static ActionResult failure(JobRecord job, String actionType, int order, String errorMessage) {
        return failure(job, actionType, order, errorMessage, null);
    }

    public static ActionResult failure(JobRecord job, String actionType, int order,
                                       String errorMessage, String description) {
        ActionResult r = new ActionResult();
        r.job = job;
        r.actionType = actionType;
        r.description = description;
        r.executionOrder = order;
        r.success = false;
        r.errorMessage = errorMessage;
        return r;
    }
}
