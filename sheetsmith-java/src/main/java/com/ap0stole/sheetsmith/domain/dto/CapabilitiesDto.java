package com.ap0stole.sheetsmith.domain.dto;

/**
 * @param chatEnabled       whether this instance has a chat at all
 * @param suggestionsEnabled whether "what would you improve?" can run — it inspects real cell
 *                          values, so it goes with the chat
 * @param sendsOnlyStructure whether the only thing reaching the model is the sheet's structure:
 *                          sheet names, column headers, ranges and existing formula text
 * @param authEnabled       whether this instance asks who you are. The UI needs it before it can
 *                          decide whether to show a login screen or a user list at all — and an
 *                          older build that does not report it is telling the truth by omission,
 *                          since it has no authentication either
 */
public record CapabilitiesDto(boolean chatEnabled, boolean suggestionsEnabled, boolean sendsOnlyStructure,
                              boolean authEnabled) {

    public static CapabilitiesDto of(boolean chatEnabled, boolean authEnabled) {
        return new CapabilitiesDto(chatEnabled, chatEnabled, !chatEnabled, authEnabled);
    }
}
