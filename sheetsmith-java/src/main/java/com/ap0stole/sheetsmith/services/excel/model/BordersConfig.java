package com.ap0stole.sheetsmith.services.excel.model;

import lombok.Data;

/**
 * {@code sides} is a comma-separated list so "outline" and "top" can be asked for together — the
 * common "box it, and underline the header" request in one step.
 */
@Data
public class BordersConfig {
    private String range;
    private String sides;
    private String style;
    private String color;
    private String sheetName;
    private Integer sheetIndex;
}
