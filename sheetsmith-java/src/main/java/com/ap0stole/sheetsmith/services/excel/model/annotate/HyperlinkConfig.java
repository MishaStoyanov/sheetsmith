package com.ap0stole.sheetsmith.services.excel.model.annotate;

import lombok.Data;

/**
 * HYPERLINK. Either {@code cell} plus {@code address} for one link, or {@code range} on its own to
 * turn a column of addresses that are already there into links.
 */
@Data
public class HyperlinkConfig {
    private String cell;
    private String range;
    private String address;
    private String text;
    private String linkType;
    private String sheetName;
    private Integer sheetIndex;
}
