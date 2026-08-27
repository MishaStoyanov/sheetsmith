package com.ap0stole.sheetsmith.services.excel.model.annotate;

import lombok.Data;

/** COMMENT. A note pinned to one cell, or {@code remove} to take an existing one off. */
@Data
public class CommentConfig {
    private String cell;
    private String text;
    private String author;
    private Boolean remove;
    private String sheetName;
    private Integer sheetIndex;
}
