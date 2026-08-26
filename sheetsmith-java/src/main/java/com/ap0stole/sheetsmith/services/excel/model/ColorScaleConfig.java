package com.ap0stole.sheetsmith.services.excel.model;

import lombok.Data;

/**
 * COLOR_SCALE. The number of colours given is the number of control points: min and max alone make
 * a two-colour scale, adding mid makes three. Naming them beats a positional list because a model
 * that gets the order wrong silently inverts the meaning of the whole column.
 */
@Data
public class ColorScaleConfig {
    private String range;
    private String minColor;
    private String midColor;
    private String maxColor;
    private String sheetName;
    private Integer sheetIndex;
}
