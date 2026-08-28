package com.ap0stole.sheetsmith.domain.dto.chat;

import jakarta.validation.constraints.Min;

/**
 * Undo, by naming what to go back to.
 *
 * @param revision the revision to restore. Reverting does not erase what came after: it commits
 *                 the old content as the next revision, so an undo can itself be undone
 */
public record RevertRequest(
        @Min(0) int revision
) {
}
