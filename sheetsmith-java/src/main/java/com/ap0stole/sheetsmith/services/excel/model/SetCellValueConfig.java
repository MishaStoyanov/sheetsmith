package com.ap0stole.sheetsmith.services.excel.model;

import lombok.Data;

/**
 * {@code value} is deliberately an {@code Object}: the JSON type the model sent is itself evidence
 * of what the user meant, and collapsing it to a String here would throw that away before
 * {@code SET_CELL_VALUE} ever sees it.
 */
@Data
public class SetCellValueConfig {
    private String cell;
    private String range;
    private Object value;
    private String valueType;
    private String sheetName;
    private Integer sheetIndex;
}
