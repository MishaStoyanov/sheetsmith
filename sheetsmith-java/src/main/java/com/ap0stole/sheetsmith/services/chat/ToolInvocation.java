package com.ap0stole.sheetsmith.services.chat;

/**
 * Outcome of one tool call inside a chat turn — both what the user will read
 * ({@code humanText}) and what goes back to the model ({@code data}).
 */
public record ToolInvocation(
        String tool,
        boolean mutating,
        boolean success,
        String humanText,
        String resultPreview,
        Object data,
        String error
) {

    public static ToolInvocation ok(String tool, boolean mutating, String humanText,
                                    String resultPreview, Object data) {
        return new ToolInvocation(tool, mutating, true, humanText, resultPreview, data, null);
    }

    public static ToolInvocation failed(String tool, boolean mutating, String humanText, String error) {
        return new ToolInvocation(tool, mutating, false, humanText, null, null, error);
    }
}
