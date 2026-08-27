package com.ap0stole.sheetsmith.services.excel.model.table;

import lombok.Data;

/**
 * CREATE_TABLE. {@code range} has to include the header row — an Excel table is defined by its
 * headings, which is also why they cannot be blank or repeated.
 */
@Data
public class CreateTableConfig {
    private String range;
    private String name;
    private String style;
    private String sheetName;
    private Integer sheetIndex;
}
