package com.ap0stole.sheetsmith.services.excel.model;

import lombok.Data;

/**
 * PAGE_SETUP. Every key is optional and each one is a separate thing to set, so the handler applies
 * what it was given and refuses only a step that asked for nothing at all.
 */
@Data
public class PageSetupConfig {
    private String orientation;
    private Integer fitToWidth;
    private Integer fitToHeight;
    private String printArea;
    private String repeatHeaderRows;
    private String repeatHeaderColumns;
    private String paperSize;
    private Boolean printGridlines;
    private String sheetName;
    private Integer sheetIndex;
}
