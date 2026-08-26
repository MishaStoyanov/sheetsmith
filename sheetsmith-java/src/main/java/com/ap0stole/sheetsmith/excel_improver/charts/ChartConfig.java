package com.ap0stole.sheetsmith.excel_improver.charts;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

@Data
public class ChartConfig {
    private String sourceRange;
    @JsonAlias("sheetName")
    private String sourceSheet;
    private Integer sourceSheetIndex;
    private String targetSheet;
    private String title;
    private String chartType;
    private Integer chartWidth;
    private Integer chartHeight;
}
