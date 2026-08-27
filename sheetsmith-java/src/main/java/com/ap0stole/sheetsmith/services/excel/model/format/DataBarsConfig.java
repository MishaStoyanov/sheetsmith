package com.ap0stole.sheetsmith.services.excel.model.format;

import lombok.Data;

/** DATA_BARS. {@code showValue} false leaves the bar alone in the cell, with the number hidden. */
@Data
public class DataBarsConfig {
    private String range;
    private String color;
    private Boolean showValue;
    private String sheetName;
    private Integer sheetIndex;
}
