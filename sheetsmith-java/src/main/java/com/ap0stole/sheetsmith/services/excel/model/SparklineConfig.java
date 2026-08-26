package com.ap0stole.sheetsmith.services.excel.model;

import lombok.Data;

/**
 * SPARKLINES. {@code range} is where the little charts go and {@code dataRange} is what they are
 * drawn from — one row of numbers per sparkline cell going down a column, one column per cell going
 * across a row.
 */
@Data
public class SparklineConfig {
    private String range;
    private String dataRange;
    private String type;
    private String color;
    private Boolean showMarkers;
    private String sheetName;
    private Integer sheetIndex;
}
