package com.ap0stole.sheetsmith.services.excel.model.formula;

import lombok.Data;

/**
 * LOOKUP_FROM_SHEET. {@code sourceColumn} takes either a 1-based position inside {@code sourceRange}
 * or a plain column letter, because a model asked for one reaches for the other about half the time
 * and both are unambiguous once the range is known.
 */
@Data
public class LookupConfig {
    private String range;
    private String keyRange;
    private String sourceRange;
    private String sourceSheet;
    private String sourceColumn;
    private String ifMissing;
    private String sheetName;
    private Integer sheetIndex;
}
