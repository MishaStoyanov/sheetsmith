package com.ap0stole.sheetsmith.services.excel.model;

import lombok.Data;

@Data
public class ConditionalFormatConfig {
    private String range;
    private String sheetName;
    private Integer sheetIndex;
    private String operator;
    private String value;
    private String backgroundColor;
    private String fontColor;
    private Boolean bold;
}
