package com.ap0stole.sheetsmith.services.excel.model;

import lombok.Data;

/**
 * {@code format} takes either a name ("currency", "percent", …) or a literal Excel pattern, because
 * the named ones cover what users ask for and the pattern covers what they ask for next.
 */
@Data
public class NumberFormatConfig {
    private String range;
    private String format;
    private Integer decimals;
    private String currencySymbol;
    private String sheetName;
    private Integer sheetIndex;
}
