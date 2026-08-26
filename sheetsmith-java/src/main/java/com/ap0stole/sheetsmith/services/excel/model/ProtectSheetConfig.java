package com.ap0stole.sheetsmith.services.excel.model;

import lombok.Data;

/**
 * PROTECT_SHEET. {@code unlockedRange} is the important one: every cell in a sheet is locked until
 * told otherwise, so protecting without it freezes the whole sheet rather than guarding the parts
 * that were meant to be guarded.
 */
@Data
public class ProtectSheetConfig {
    private String password;
    private String unlockedRange;
    private Boolean unprotect;
    private String sheetName;
    private Integer sheetIndex;
}
