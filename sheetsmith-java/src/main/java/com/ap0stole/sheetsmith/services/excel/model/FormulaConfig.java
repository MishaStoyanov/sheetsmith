package com.ap0stole.sheetsmith.services.excel.model;

import lombok.Data;

@Data
public class FormulaConfig {
    private String cell;
    private String formula;
    private String label;
    private String sheetName;
    private Integer sheetIndex;
}
