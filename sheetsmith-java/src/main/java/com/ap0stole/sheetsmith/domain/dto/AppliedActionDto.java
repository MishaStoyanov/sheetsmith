package com.ap0stole.sheetsmith.domain.dto;

import com.ap0stole.sheetsmith.domain.entity.ActionResult;

/**
 * One executed step of a job.
 *
 * @param type         the action kind, for clients that branch on it
 * @param description  the same step in plain language, which is what the interface shows
 * @param success      whether this step did what it said
 * @param errorMessage why it did not, where it did not. A run can be PARTIAL: some steps land and
 *                     others do not, and the ones that failed say so individually
 */
public record AppliedActionDto(String type, String description, boolean success, String errorMessage) {

    public static AppliedActionDto from(ActionResult result) {
        String description = (result.getDescription() == null || result.getDescription().isBlank())
                ? result.getActionType().toLowerCase().replace('_', ' ')
                : result.getDescription();
        return new AppliedActionDto(result.getActionType(), description,
                result.isSuccess(), result.getErrorMessage());
    }
}
