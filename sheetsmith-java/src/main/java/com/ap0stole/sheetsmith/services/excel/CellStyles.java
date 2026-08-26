package com.ap0stole.sheetsmith.services.excel;

import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.awt.Color;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * How every styling action changes a cell's appearance: by <em>editing</em> the style it already
 * has, never by replacing it.
 * <p>
 * That distinction is the whole reason this class exists. A cell style in xlsx is a shared,
 * immutable-in-practice record, so the obvious implementation — create a style, set the one facet
 * asked for, assign it — silently discards every other facet the cell had. It is why colouring a
 * column used to wipe the number format someone had just applied to it, and why the three styling
 * actions are one piece of work rather than three.
 * <p>
 * So each cell's current style is cloned, the requested facet is changed on the copy, and the copy
 * is assigned. Cloning per cell would exhaust the workbook's 64 000-style table on a large range,
 * so styles are cached on the pair (style the cell had, edit being made): a column of 5 000
 * identically styled cells creates exactly one new style, and the cell in it that was already bold
 * creates a second.
 */
public final class CellStyles {

    /**
     * Ceiling on one styling step. Styling reaches every cell in the range and creates the ones
     * that do not exist yet, so an unbounded range is an unbounded file.
     */
    public static final int MAX_CELLS = 100_000;

    private CellStyles() {
    }

    /**
     * One cell's worth of change, applied to a copy of the style that cell already has.
     * <p>
     * {@link #key} is what makes the cache safe for edits that differ across the range: borders
     * drawn around the outside of a block change a corner cell differently from an edge cell, and
     * two cells may share a resulting style only when they started from the same style
     * <em>and</em> are having the same thing done to them.
     */
    public interface StyleEdit {

        /**
         * Identifies this cell's variant of the edit; cells sharing a key may share a style.
         * Null means this cell has nothing done to it — it is then left untouched and, if it does
         * not exist, uncreated, so an edit that reaches only the rim of a block does not fill the
         * middle with empty cells carrying a style identical to the one they had.
         */
        String key(int row, int column);

        void apply(XSSFCellStyle style, int row, int column);
    }

    /**
     * Applies {@code edit} to every cell of {@code area}, creating rows and cells that do not exist
     * yet — a border around an empty box, or a number format waiting for values, are both real
     * requests.
     *
     * @return how many cells were touched
     */
    public static int apply(XSSFWorkbook workbook, XSSFSheet sheet, CellRangeAddress area, StyleEdit edit) {
        Map<String, XSSFCellStyle> cache = new HashMap<>();
        int touched = 0;

        for (int r = area.getFirstRow(); r <= area.getLastRow(); r++) {
            XSSFRow row = null;
            for (int c = area.getFirstColumn(); c <= area.getLastColumn(); c++) {
                String variant = edit.key(r, c);
                if (variant == null) {
                    continue;
                }
                if (row == null) {
                    row = sheet.getRow(r);
                    if (row == null) {
                        row = sheet.createRow(r);
                    }
                }
                XSSFCell cell = row.getCell(c);
                if (cell == null) {
                    cell = row.createCell(c);
                }
                XSSFCellStyle current = cell.getCellStyle();
                int currentRow = r;
                int currentColumn = c;
                cell.setCellStyle(cache.computeIfAbsent(
                        current.getIndex() + "|" + variant,
                        ignored -> {
                            XSSFCellStyle edited = workbook.createCellStyle();
                            edited.cloneStyleFrom(current);
                            edit.apply(edited, currentRow, currentColumn);
                            return edited;
                        }));
                touched++;
            }
        }
        return touched;
    }

    /**
     * The cells a range names, as a bounded area.
     * <p>
     * A whole-column {@code "A:A"} or whole-row {@code "1:1"} reference is refused rather than
     * clamped: POI resolves them to a first row or column of -1, which survives every size check
     * and only fails inside the write — and styling all 1 048 576 rows of a column would create
     * every one of them.
     */
    public static CellRangeAddress area(String raw, String key) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("\"" + key + "\" is required, e.g. \"A1:D20\".");
        }
        CellRangeAddress area = CellRangeAddress.valueOf(
                raw.substring(raw.lastIndexOf('!') + 1).replace("$", "").trim());
        if (area.getFirstRow() < 0 || area.getFirstColumn() < 0) {
            throw new IllegalArgumentException("\"" + raw + "\" names a whole "
                    + (area.getFirstRow() < 0 ? "column" : "row")
                    + " — name a bounded range like \"A1:D20\".");
        }

        long cells = (long) (area.getLastRow() - area.getFirstRow() + 1)
                * (area.getLastColumn() - area.getFirstColumn() + 1);
        if (cells > MAX_CELLS) {
            throw new IllegalArgumentException(area.formatAsString() + " covers " + cells
                    + " cells, limit is " + MAX_CELLS + " — style the data rows that exist rather"
                    + " than the whole sheet, or split the step.");
        }
        return area;
    }

    /**
     * A {@code #RRGGBB} colour, or null when nothing was asked for.
     * <p>
     * The prompt hands the model a table of hex values, so anything else is a mistake worth naming:
     * a colour silently ignored reads to the user as an action that did nothing.
     */
    public static XSSFColor color(String hex, String key) {
        if (hex == null || hex.isBlank()) {
            return null;
        }
        String cleaned = hex.trim();
        if (!cleaned.startsWith("#")) {
            cleaned = "#" + cleaned;
        }
        if (!cleaned.matches("#[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("\"" + key + "\" has to be a hex colour like"
                    + " \"#1E3A8A\", but was \"" + hex + "\".");
        }
        return new XSSFColor(Color.decode(cleaned), null);
    }

    /** Lower-cased and trimmed, with {@code null} and blank collapsed to null. */
    public static String keyword(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
