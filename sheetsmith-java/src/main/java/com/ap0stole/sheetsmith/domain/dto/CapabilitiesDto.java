package com.ap0stole.sheetsmith.domain.dto;

/**
 * @param chatEnabled       whether this instance has a chat at all
 * @param suggestionsEnabled whether "what would you improve?" can run — it inspects real cell
 *                          values, so it goes with the chat
 * @param sendsOnlyStructure whether the only thing reaching the model is the sheet's structure:
 *                          sheet names, column headers, ranges and existing formula text
 */
public record CapabilitiesDto(boolean chatEnabled, boolean suggestionsEnabled, boolean sendsOnlyStructure) {

    public static CapabilitiesDto of(boolean chatEnabled) {
        return new CapabilitiesDto(chatEnabled, chatEnabled, !chatEnabled);
    }
}
