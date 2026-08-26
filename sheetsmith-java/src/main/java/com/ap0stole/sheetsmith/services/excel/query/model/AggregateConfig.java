package com.ap0stole.sheetsmith.services.excel.query.model;

import lombok.Data;

@Data
public class AggregateConfig {
    private String range;
    private Integer columnIndex;
    private String operation;
    private Integer groupByColumnIndex;
    private String sheetName;
    private Integer sheetIndex;
}
