package com.ap0stole.sheetsmith.services.excel.model;

import lombok.Data;

/**
 * ADD_TOTALS_ROW. {@code range} is the data being totalled, and the totals land in the row directly
 * beneath it.
 */
@Data
public class TotalsRowConfig {
    private String range;
    private String function;
    private String label;
    private String sheetName;
    private Integer sheetIndex;
}
