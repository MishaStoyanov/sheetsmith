package com.ap0stole.sheetsmith.services.excel.model.structure;

import lombok.Data;

@Data
public class FilterConfig {
    private String range;
    private String sheetName;
    private Integer sheetIndex;
}
