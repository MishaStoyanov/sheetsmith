package com.ap0stole.sheetsmith.services.excel.model;

import lombok.Data;

/**
 * GROUP_BY. Columns are named the way LOOKUP_FROM_SHEET names its own — a letter, or a position
 * inside {@code range} — so the two actions that reach across a table agree on what "column C" means.
 */
@Data
public class GroupByConfig {
    private String range;
    private String groupBy;
    private String valueColumn;
    private String function;
    private String targetSheet;
    private String targetCell;
    private Boolean hasHeader;
    private String sheetName;
    private Integer sheetIndex;
}
