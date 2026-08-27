package com.ap0stole.sheetsmith.services.excel.actions.format;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;

/**
 * The part COLOR_SCALE and DATA_BARS share: both paint a range by magnitude, and Excel paints only
 * cells that hold a number. It ignores the rest in silence — so a scale over a column of text is a
 * rule that renders nothing, which reaches the user as a step that claims to have worked and did
 * not. Counting first is the only way the step can say otherwise.
 */
final class ValueScale {

    private ValueScale() {}

    /**
     * What a range actually holds, from the point of view of a rule that only paints numbers.
     * Blanks are counted apart from text because they are not a problem worth reporting: a range
     * chosen with room to grow is normal, a range of labels is a mistake.
     */
    record Coverage(int numeric, int text, int blank) {
    }

    public static Coverage coverage(XSSFSheet sheet, CellRangeAddress area) {
        int numeric = 0;
        int text = 0;
        int blank = 0;
        for (int r = area.getFirstRow(); r <= area.getLastRow(); r++) {
            Row row = sheet.getRow(r);
            for (int c = area.getFirstColumn(); c <= area.getLastColumn(); c++) {
                Cell cell = row == null ? null : row.getCell(c);
                if (isNumeric(cell)) {
                    numeric++;
                } else if (cell == null || cell.getCellType() == CellType.BLANK) {
                    blank++;
                } else {
                    text++;
                }
            }
        }
        return new Coverage(numeric, text, blank);
    }

    /**
     * A formula counts by its cached result: the workbook is not recalculated until it is saved, so
     * the cached type is all there is to go on, and it is also what Excel paints from.
     */
    private static boolean isNumeric(Cell cell) {
        if (cell == null) {
            return false;
        }
        CellType type = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType() : cell.getCellType();
        return type == CellType.NUMERIC;
    }

    /** What the step reports back, or null when every cell in the range holds a number. */
    public static String detail(Coverage coverage) {
        if (coverage.numeric() == 0) {
            return coverage.text() == 0
                    ? "the range holds no values yet, so nothing is painted until it does"
                    : "no cell in the range holds a number — Excel paints only numbers, so nothing shows";
        }
        if (coverage.text() > 0) {
            return coverage.text() + (coverage.text() == 1
                    ? " cell holds text rather than a number and stays unpainted"
                    : " cells hold text rather than a number and stay unpainted");
        }
        return null;
    }
}
