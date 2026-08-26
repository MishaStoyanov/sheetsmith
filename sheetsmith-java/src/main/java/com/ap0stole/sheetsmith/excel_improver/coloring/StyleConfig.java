package com.ap0stole.sheetsmith.excel_improver.coloring;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

import java.util.Map;

/**
 * FORMAT_CELLS' keys. Every one is a {@code Boolean} or an object rather than a primitive, because
 * the difference between "not mentioned" and "asked to be off" is the difference between leaving a
 * bold header bold and quietly un-bolding it.
 * <p>
 * The two alias setters accept the nested shapes a model sometimes invents —
 * {@code {"colors": {"color": "#FFF"}}}, {@code {"fonts": {"bold": true}}} — rather than losing the
 * instruction to a key nobody reads.
 */
@Data
public class StyleConfig {
    private String range;
    private String sheetName;
    private Integer sheetIndex;

    private String backgroundColor;
    private String fontColor;
    private Boolean bold;
    private Integer fontSize;

    @JsonAlias("backgroundColor")
    public void setFromColors(Map<String, Object> colors) {
        if (colors != null && colors.get("color") instanceof String color) {
            this.backgroundColor = color;
        }
    }

    @JsonAlias("fonts")
    public void setFromFonts(Map<String, Object> fonts) {
        if (fonts == null) {
            return;
        }
        if (fonts.get("bold") instanceof Boolean bold) {
            this.bold = bold;
        }
        if (fonts.get("color") instanceof String color) {
            this.fontColor = color;
        }
    }
}
