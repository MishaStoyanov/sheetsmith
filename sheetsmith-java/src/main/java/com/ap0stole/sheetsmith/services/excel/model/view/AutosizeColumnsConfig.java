package com.ap0stole.sheetsmith.services.excel.model.view;

import lombok.Data;

@Data
public class AutosizeColumnsConfig {
    /** Columns to size, as {@code "A:D"} or an ordinary range whose columns are used. Null means the whole used sheet. */
    private String range;

    /** Ceiling in characters, so one long cell cannot produce a column nobody can scroll past. */
    private Integer maxWidth;

    private String sheetName;
    private Integer sheetIndex;
}
