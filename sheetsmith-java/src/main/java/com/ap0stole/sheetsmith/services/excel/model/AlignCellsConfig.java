package com.ap0stole.sheetsmith.services.excel.model;

import lombok.Data;

/**
 * Every key is optional individually but the action needs one of them: {@code wrapText} is a
 * {@code Boolean} rather than a {@code boolean} so that "not mentioned" stays distinguishable from
 * "asked to be off", which is the difference between keeping a cell's wrapping and removing it.
 */
@Data
public class AlignCellsConfig {
    private String range;
    private String horizontal;
    private String vertical;
    private Boolean wrapText;
    private Integer indent;
    private String sheetName;
    private Integer sheetIndex;
}
