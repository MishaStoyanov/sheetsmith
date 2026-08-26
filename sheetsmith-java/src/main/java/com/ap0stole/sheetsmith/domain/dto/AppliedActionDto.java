package com.ap0stole.sheetsmith.domain.dto;

import com.ap0stole.sheetsmith.domain.entity.ActionResult;

/**
 * One executed step of a job. {@code description} is what the UI shows; {@code type} stays for
 * clients that branch on the action kind.
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
