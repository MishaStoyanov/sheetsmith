package com.ap0stole.sheetsmith.services.excel.model;

import lombok.Data;

@Data
public class SortConfig {
    private String range;
    private int columnIndex;
    private boolean ascending = true;
    private String sheetName;
    private Integer sheetIndex;
}
