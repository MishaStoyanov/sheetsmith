package com.ap0stole.sheetsmith.domain.dto.chat;

/**
 * Result of one chat turn. {@code mutated} tells the frontend whether it must re-fetch the
 * working copy to refresh the grid.
 */
public record ChatTurnDto(
        ChatMessageDto message,
        boolean mutated,
        int revision
) {
}
