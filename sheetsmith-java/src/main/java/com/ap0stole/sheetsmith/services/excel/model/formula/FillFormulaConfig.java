package com.ap0stole.sheetsmith.services.excel.model.formula;

import lombok.Data;

/**
 * FILL_FORMULA. {@code formula} is optional: the top cell of the range may already hold the formula
 * to fill from, which is the shape a plan takes when ADD_FORMULA wrote it a step earlier.
 */
@Data
public class FillFormulaConfig {
    private String range;
    private String formula;
    private String sheetName;
    private Integer sheetIndex;
}
