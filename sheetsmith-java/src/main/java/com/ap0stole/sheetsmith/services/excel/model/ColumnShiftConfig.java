package com.ap0stole.sheetsmith.services.excel.model;

import lombok.Data;

/**
 * INSERT_COLUMNS and DELETE_COLUMNS. {@code at} is a column letter, because that is what a user
 * points at; {@code range} accepts the {@code "C:E"} form for naming several at once.
 */
@Data
public class ColumnShiftConfig {
    private String at;
    private Integer count;
    private String range;
    private String sheetName;
    private Integer sheetIndex;
}
