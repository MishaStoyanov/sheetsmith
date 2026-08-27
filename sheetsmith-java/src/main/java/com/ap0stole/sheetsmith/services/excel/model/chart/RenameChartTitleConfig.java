package com.ap0stole.sheetsmith.services.excel.model.chart;

import lombok.Data;

@Data
public class RenameChartTitleConfig {
    private String newTitle;
    private String sheetName;
    private Integer sheetIndex;
    private Integer chartIndex;
}
