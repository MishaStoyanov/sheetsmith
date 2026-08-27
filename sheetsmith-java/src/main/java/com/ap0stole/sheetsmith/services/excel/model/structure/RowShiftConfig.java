package com.ap0stole.sheetsmith.services.excel.model.structure;

import lombok.Data;

/**
 * INSERT_ROWS and DELETE_ROWS. {@code at} is a row number as Excel shows it, counting from 1 —
 * the 0-based index every other part of POI uses is an implementation detail no prompt should have
 * to know about.
 */
@Data
public class RowShiftConfig {
    private Integer at;
    private Integer count;
    private String range;
    private String sheetName;
    private Integer sheetIndex;
}
