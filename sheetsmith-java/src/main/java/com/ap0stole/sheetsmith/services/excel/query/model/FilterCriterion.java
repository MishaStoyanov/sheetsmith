package com.ap0stole.sheetsmith.services.excel.query.model;

import lombok.Data;

@Data
public class FilterCriterion {
    private Integer columnIndex;
    private String operator;
    private String value;
}
