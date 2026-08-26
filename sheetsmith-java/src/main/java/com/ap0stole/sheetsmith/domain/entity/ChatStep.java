package com.ap0stole.sheetsmith.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One tool invocation the agent made while answering. Stored so the user can always open
 * "how I got there" and read, in plain words, the chain that produced the answer.
 */
@Entity
@Table(name = "chat_steps")
@Getter
@Setter
@NoArgsConstructor
public class ChatStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private ChatMessage message;

    @Column(nullable = false)
    private int executionOrder;

    @Column(nullable = false)
    private String toolName;

    /** Plain-language description shown to the user — never raw enums. */
    @Column(columnDefinition = "TEXT")
    private String humanText;

    /** The arguments the model chose, kept for debugging and for power users. */
    @Column(columnDefinition = "TEXT")
    private String argsJson;

    /** Short rendering of what came back, e.g. "Widget A — 1240". */
    @Column(columnDefinition = "TEXT")
    private String resultPreview;

    @Column(nullable = false)
    private boolean success;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false)
    private boolean mutating;

    public static ChatStep of(ChatMessage message, int order, String toolName, boolean mutating) {
        ChatStep step = new ChatStep();
        step.message = message;
        step.executionOrder = order;
        step.toolName = toolName;
        step.mutating = mutating;
        step.success = true;
        return step;
    }
}
