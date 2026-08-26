package com.ap0stole.sheetsmith.excel_improver.coloring;

import com.ap0stole.sheetsmith.services.excel.CellStyles;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * The colours and font weight behind FORMAT_CELLS.
 * <p>
 * It edits each cell's existing style through {@link CellStyles} rather than assigning a freshly
 * built one, which is not a refinement: a new style carries default everything, so colouring a
 * column used to silently strip the number format, borders and alignment it had — and now that
 * NUMBER_FORMAT, SET_BORDERS and ALIGN_CELLS exist, a plan that formats and then colours would have
 * undone its own earlier steps.
 * <p>
 * For the same reason a font is cloned from the cell's own font and only the facets that were asked
 * for are changed, so bolding a header does not reset the typeface someone chose.
 */
@Slf4j
public class StyleHandler {

    public void execute(XSSFWorkbook workbook, StyleConfig config) {
        XSSFSheet sheet = SheetResolver.resolve(workbook, config.getSheetName(), config.getSheetIndex());

        CellRangeAddress area = CellStyles.area(config.getRange(), "range");
        XSSFColor background = CellStyles.color(config.getBackgroundColor(), "backgroundColor");
        XSSFColor fontColor = CellStyles.color(config.getFontColor(), "fontColor");
        Boolean bold = config.getBold();
        Integer fontSize = fontSize(config.getFontSize());

        if (background == null && fontColor == null && bold == null && fontSize == null) {
            throw new IllegalArgumentException("Nothing to format — name at least one of"
                    + " \"backgroundColor\", \"fontColor\", \"bold\" or \"fontSize\".");
        }

        String variant = "format:" + (background == null ? "-" : background.getARGBHex())
                + ":" + (fontColor == null ? "-" : fontColor.getARGBHex()) + ":" + bold + ":" + fontSize;

        int touched = CellStyles.apply(workbook, sheet, area, new CellStyles.StyleEdit() {
            @Override
            public String key(int row, int column) {
                return variant;
            }

            @Override
            public void apply(XSSFCellStyle style, int row, int column) {
                if (background != null) {
                    style.setFillForegroundColor(background);
                    style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                }
                if (fontColor != null || bold != null || fontSize != null) {
                    style.setFont(font(workbook, style, fontColor, bold, fontSize));
                }
            }
        });

        log.info("FORMAT_CELLS on {} of '{}': {} cell(s)", area.formatAsString(), sheet.getSheetName(), touched);
    }

    /**
     * The cell's own font with the requested facets changed. A workbook's font table is small and
     * POI reuses an identical font rather than adding another, so building one per style variant
     * costs nothing.
     */
    private XSSFFont font(XSSFWorkbook workbook, XSSFCellStyle style,
                          XSSFColor color, Boolean bold, Integer size) {
        XSSFFont current = style.getFont();
        XSSFFont font = workbook.createFont();
        font.setFontName(current.getFontName());
        font.setFontHeightInPoints(current.getFontHeightInPoints());
        font.setBold(current.getBold());
        font.setItalic(current.getItalic());
        font.setUnderline(current.getUnderline());
        font.setStrikeout(current.getStrikeout());
        if (current.getXSSFColor() != null) {
            font.setColor(current.getXSSFColor());
        }

        if (bold != null) {
            font.setBold(bold);
        }
        if (size != null) {
            font.setFontHeightInPoints(size.shortValue());
        }
        if (color != null) {
            font.setColor(color);
        }
        return font;
    }

    /** Excel's own range. A size outside it makes a file Excel offers to repair. */
    private Integer fontSize(Integer requested) {
        if (requested == null) {
            return null;
        }
        if (requested < 1 || requested > 409) {
            throw new IllegalArgumentException("\"fontSize\" has to be between 1 and 409 points,"
                    + " but was " + requested + ".");
        }
        return requested;
    }
}
