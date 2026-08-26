package com.ap0stole.sheetsmith.domain.dto.chat;

import com.ap0stole.sheetsmith.domain.entity.ChatStep;
import com.ap0stole.sheetsmith.services.chat.ToolInvocation;

/**
 * One entry of the "how I got there" chain. {@code text} is the plain-language line the user
 * reads; {@code tool} and {@code args} are there for the curious.
 */
public record ChatStepDto(
        int order,
        String tool,
        String text,
        String resultPreview,
        String args,
        boolean success,
        String error,
        boolean mutating
) {

    public static ChatStepDto from(ChatStep step) {
        return new ChatStepDto(
                step.getExecutionOrder(),
                step.getToolName(),
                step.getHumanText(),
                step.getResultPreview(),
                step.getArgsJson(),
                step.isSuccess(),
                step.getErrorMessage(),
                step.isMutating());
    }

    /**
     * A step as it happens, before anything is persisted — what the streaming endpoint pushes.
     * {@code args} stays empty: the raw view is a detail of the finished message, not of the
     * live one, and the tool call is not rendered as JSON until the turn is written down.
     */
    public static ChatStepDto live(ToolInvocation invocation, int order) {
        return new ChatStepDto(
                order,
                invocation.tool(),
                invocation.humanText(),
                invocation.resultPreview(),
                null,
                invocation.success(),
                invocation.error(),
                invocation.mutating());
    }
}
