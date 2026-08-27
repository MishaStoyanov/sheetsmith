package com.ap0stole.sheetsmith.services.excel.model.cell;

import lombok.Data;

@Data
public class RenameColumnConfig {
    private String cell;
    private String newName;
    private String sheetName;
    private Integer sheetIndex;
}
