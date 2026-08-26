package com.ap0stole.sheetsmith.services.excel.query.model;

import lombok.Data;

@Data
public class DescribeColumnConfig {
    private String range;
    private Integer columnIndex;
    private String sheetName;
    private Integer sheetIndex;
}
