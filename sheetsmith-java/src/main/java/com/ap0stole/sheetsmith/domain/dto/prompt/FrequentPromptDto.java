package com.ap0stole.sheetsmith.domain.dto.prompt;

import java.time.LocalDateTime;

/**
 * One phrasing this person has used more than once.
 *
 * @param text     their own words, unchanged — shortening it here would take the decision about
 *                 what matters in a sentence away from the person who wrote it
 * @param uses     how many times it has been sent
 * @param lastUsed when it was last sent, so the most recent of two equally frequent prompts wins
 */
public record FrequentPromptDto(String text, long uses, LocalDateTime lastUsed) {
}
