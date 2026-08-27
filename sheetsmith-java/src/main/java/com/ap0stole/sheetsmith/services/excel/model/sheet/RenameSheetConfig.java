package com.ap0stole.sheetsmith.services.excel.model.sheet;

import lombok.Data;

@Data
public class RenameSheetConfig {
    private String newName;
    private String sheetName;
    private Integer sheetIndex;
}
