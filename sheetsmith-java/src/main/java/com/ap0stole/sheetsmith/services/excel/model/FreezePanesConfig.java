package com.ap0stole.sheetsmith.services.excel.model;

import lombok.Data;

/**
 * Counts, not indices — {@code rows = 3} freezes rows 1 to 3 whatever the header row happens to be,
 * so there is no 0-based/1-based question to get wrong. Both null means "freeze the header row";
 * both zero means unfreeze.
 */
@Data
public class FreezePanesConfig {
    private Integer rows;
    private Integer columns;
    private String sheetName;
    private Integer sheetIndex;
}
