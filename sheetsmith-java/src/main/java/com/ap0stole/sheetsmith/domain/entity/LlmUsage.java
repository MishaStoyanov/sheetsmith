package com.ap0stole.sheetsmith.domain.entity;

import com.ap0stole.sheetsmith.domain.enums.UsageKind;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One call to a model: who caused it, what was asked, what it cost and which engine answered.
 * <p>
 * One row per call rather than per run or per turn — a repaired run makes two calls and a chat turn
 * makes one per step, so summing rows is the whole of "how much was spent" with no special cases.
 * <p>
 * What was <em>done</em> is deliberately not here. The steps live in {@code action_results} and
 * {@code chat_steps}; this row points at the run or the session so they can be read from where they
 * already are, rather than kept twice and allowed to drift.
 */
@Entity
@Table(name = "llm_usage")
@Getter
@Setter
@NoArgsConstructor
public class LlmUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UsageKind kind;

    /** Null where nobody was signed in — the same honesty as a run with no owner. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id")
    private JobRecord job;

    @Column(name = "session_id")
    private String sessionId;

    /** What the person asked for, in their words. Already stored for both flows; kept here so the
     *  spend can be read without joining back to whichever table the call came from. */
    @Column(columnDefinition = "TEXT")
    private String prompt;

    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;

    @Column(name = "provider_mode")
    private String providerMode;

    private String provider;
    private String model;

    /**
     * What the model charged per million tokens when this call was made.
     * <p>
     * Written here rather than looked up on every read, because a price is a fact about a moment.
     * Read from the list at query time, correcting today's figure would move last March's chart —
     * an audit whose numbers change without anybody deciding they should is not an audit.
     * <p>
     * The rates, not the total: a stored sum cannot tell a cheap call from a mispriced one. Null
     * where the model had no price, which is not the same as free.
     */
    @Column(name = "input_per_million", precision = 12, scale = 4)
    private java.math.BigDecimal inputPerMillion;

    @Column(name = "output_per_million", precision = 12, scale = 4)
    private java.math.BigDecimal outputPerMillion;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    @Column(nullable = false)
    private LocalDateTime finishedAt;
}
