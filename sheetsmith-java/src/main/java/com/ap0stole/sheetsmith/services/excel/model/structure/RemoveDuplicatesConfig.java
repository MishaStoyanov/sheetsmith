package com.ap0stole.sheetsmith.services.excel.model.structure;

import lombok.Data;

/**
 * REMOVE_DUPLICATES. {@code hasHeader} is a {@code Boolean} so that "not mentioned" can default to
 * true — a range naming a header row is the common case, and comparing the header against the data
 * would at best waste a row.
 */
@Data
public class RemoveDuplicatesConfig {
    private String range;
    private String columns;
    private Boolean hasHeader;
    private String sheetName;
    private Integer sheetIndex;
}
