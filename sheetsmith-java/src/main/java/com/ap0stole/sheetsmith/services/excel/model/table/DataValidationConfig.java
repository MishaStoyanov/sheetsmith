package com.ap0stole.sheetsmith.services.excel.model.table;

import lombok.Data;

/**
 * DATA_VALIDATION. The bounds are Strings rather than numbers because POI stores them as formula
 * text and a date bound is a date string — one type that carries every case beats three that each
 * carry one.
 */
@Data
public class DataValidationConfig {
    private String range;
    private String type;
    private String values;
    private String sourceRange;
    private String operator;
    private String min;
    private String max;
    private String value;
    private Boolean allowBlank;
    private Boolean strict;
    private String errorTitle;
    private String errorMessage;
    private String sheetName;
    private Integer sheetIndex;
}
