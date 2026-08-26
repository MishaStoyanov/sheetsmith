package com.ap0stole.sheetsmith.services.excel.query.model;

import lombok.Data;

@Data
public class ReadRangeConfig {
    private String range;
    private String sheetName;
    private Integer sheetIndex;
}
