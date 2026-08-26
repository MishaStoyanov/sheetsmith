package com.ap0stole.sheetsmith.services.excel.model;

import lombok.Data;

/**
 * DELETE_SHEET. {@code name} and {@code sheetName} both work: the catalog documents "name" to match
 * ADD_SHEET, and "sheetName" is what every other action calls the same thing, so a model reaching
 * for either is answered rather than corrected.
 */
@Data
public class SheetTargetConfig {
    private String name;
    private String sheetName;
    private Integer sheetIndex;
}
