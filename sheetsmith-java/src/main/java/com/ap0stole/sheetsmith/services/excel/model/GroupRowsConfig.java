package com.ap0stole.sheetsmith.services.excel.model;

import lombok.Data;

/**
 * GROUP_ROWS. Rows are named the way DELETE_ROWS names them — a {@code range} like "5:20", or
 * {@code at} plus {@code count} — because a row span should not mean two different things depending
 * on which action reads it.
 */
@Data
public class GroupRowsConfig {
    private String range;
    private Integer at;
    private Integer count;
    private Boolean collapsed;
    private Boolean ungroup;
    private Boolean summaryBelow;
    private String sheetName;
    private Integer sheetIndex;
}
