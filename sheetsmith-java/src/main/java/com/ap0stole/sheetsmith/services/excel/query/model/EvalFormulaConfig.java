package com.ap0stole.sheetsmith.services.excel.query.model;

import lombok.Data;

@Data
public class EvalFormulaConfig {
    private String formula;
    private String sheetName;
    private Integer sheetIndex;
}
