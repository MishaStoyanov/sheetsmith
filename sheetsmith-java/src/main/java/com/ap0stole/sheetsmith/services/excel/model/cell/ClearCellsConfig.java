package com.ap0stole.sheetsmith.services.excel.model.cell;

import lombok.Data;

@Data
public class ClearCellsConfig {
    private String range;
    private String sheetName;
    private Integer sheetIndex;
}
