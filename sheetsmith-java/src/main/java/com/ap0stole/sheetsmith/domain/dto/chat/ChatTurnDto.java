package com.ap0stole.sheetsmith.domain.dto.chat;

/**
 * Result of one chat turn.
 *
 * @param message  the answer, with the chain of steps that produced it
 * @param mutated  whether the sheet changed. The grid re-fetches the working copy when it did,
 *                 and leaves it alone when the turn only answered a question
 * @param revision the revision after the turn — the same number if nothing was written
 */
public record ChatTurnDto(
        ChatMessageDto message,
        boolean mutated,
        int revision
) {
}
