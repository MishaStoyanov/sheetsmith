package com.ap0stole.sheetsmith.excel_improver.charts;

import lombok.Data;

@Data
public class RenameChartTitleConfig {
    private String newTitle;
    private String sheetName;
    private Integer sheetIndex;
    private Integer chartIndex;
}
