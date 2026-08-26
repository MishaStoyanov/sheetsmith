package com.ap0stole.sheetsmith.services.excel.query.model;

import lombok.Data;

import java.util.List;

@Data
public class FindRowsConfig {
    private String range;
    private List<FilterCriterion> filters;
    private Integer sortColumnIndex;
    private boolean ascending = true;
    private Integer limit;
    private List<Integer> columns;
    private String sheetName;
    private Integer sheetIndex;
}
