package com.ap0stole.sheetsmith.services.excel.actions.structure;

import com.ap0stole.sheetsmith.services.excel.FormulaErrorScanner;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.util.List;
import java.util.Locale;

/**
 * What the four structural actions — inserting and deleting rows and columns — have in common.
 * <p>
 * They are the only actions that move cells other than the ones they were pointed at, which makes
 * their honest failure mode a formula somewhere else in the workbook turning into {@code #REF!}.
 * POI rewrites the references it can, so the step's job is to say what it could not: every one of
 * these actions scans the workbook's formulas before and after and reports the difference, because
 * a broken formula is a silent result otherwise — the sheet saves, the step succeeds, and a total
 * three sheets away has quietly become an error.
 */
public final class StructureShift {

    /**
     * Ceiling on one insert. Excel's own limits are far higher, but a model asking to insert a
     * thousand rows has almost always misread the sheet, and the cost of being wrong is a file the
     * user has to undo rather than a step they can repeat.
     */
    public static final int MAX_INSERT = 1_000;

    public static final int EXCEL_MAX_ROWS = 1_048_576;

    public static final int EXCEL_MAX_COLUMNS = 16_384;

    private static final FormulaErrorScanner SCANNER = new FormulaErrorScanner();

    private StructureShift() {
    }

    public static List<FormulaErrorScanner.CellError> formulaErrors(XSSFWorkbook workbook) {
        return SCANNER.scan(workbook);
    }

    /**
     * The sentence a structural step adds to its description: how much moved, and what broke.
     *
     * @param what   "row" or "column", singular — pluralised here
     * @param before the formula errors the workbook held before the shift
     */
    public static String report(String action, String what, int count, String where,
                         XSSFWorkbook workbook, List<FormulaErrorScanner.CellError> before) {
        return count + " " + what + (count == 1 ? "" : "s") + " " + action + " " + where
                + brokenFormulas(workbook, before);
    }

    /**
     * The clause naming formulas this operation broke, or nothing when it broke none.
     * <p>
     * Shared with DELETE_SHEET, which breaks references the same way without moving anything. The
     * count check is the part worth keeping: an error that was already there but has <em>moved</em>
     * reads as new when errors are matched by address, and these are the operations that move things
     * — an earlier step's {@code #REF!} at D1 becomes a "new" one at E1 the moment a column is
     * inserted before it. Nothing is newly wrong unless there is more wrong than there was.
     */
    public static String brokenFormulas(XSSFWorkbook workbook, List<FormulaErrorScanner.CellError> before) {
        List<FormulaErrorScanner.CellError> after = formulaErrors(workbook);
        List<FormulaErrorScanner.CellError> broken = FormulaErrorScanner.newErrors(before, after);
        if (broken.isEmpty() || after.size() <= before.size()) {
            return "";
        }
        // Named individually up to the scanner's own cap: "some formulas broke" is not something a
        // user can act on, and the model reading this trace needs the addresses to repair them.
        StringBuilder text = new StringBuilder(", and ").append(broken.size())
                .append(broken.size() == 1 ? " formula now shows " : " formulas now show ")
                .append("an error (")
                .append(broken.stream().limit(5).map(FormulaErrorScanner.CellError::label)
                        .reduce((a, b) -> a + ", " + b).orElse(""));
        if (broken.size() > 5) {
            text.append(", …");
        }
        return text.append(") — they pointed at cells this step moved or removed").toString();
    }

    /** A 1-based row number as the user and the model both write it, checked against Excel's limit. */
    public static int row(Integer at, String key) {
        if (at == null) {
            throw new IllegalArgumentException("\"" + key + "\" is required — the row number to work"
                    + " at, counting from 1 as Excel does.");
        }
        if (at < 1 || at > EXCEL_MAX_ROWS) {
            throw new IllegalArgumentException("\"" + key + "\" has to be a row number between 1 and "
                    + EXCEL_MAX_ROWS + ", but was " + at + ".");
        }
        return at - 1;
    }

    /**
     * A column named the way a spreadsheet names one — {@code "C"} — as a 0-based index. A bare
     * number is accepted too and read as 1-based, because a model that has been counting rows in
     * one key tends to keep counting in the next.
     */
    public static int column(String at, String key) {
        if (at == null || at.isBlank()) {
            throw new IllegalArgumentException("\"" + key + "\" is required — the column to work at,"
                    + " e.g. \"C\".");
        }
        String cleaned = at.trim().replace("$", "").toUpperCase(Locale.ROOT);
        if (cleaned.matches("\\d+")) {
            int number = Integer.parseInt(cleaned);
            if (number < 1 || number > EXCEL_MAX_COLUMNS) {
                throw new IllegalArgumentException("\"" + key + "\" has to name a column like \"C\","
                        + " or a column number between 1 and " + EXCEL_MAX_COLUMNS + ".");
            }
            return number - 1;
        }
        if (!cleaned.matches("[A-Z]{1,3}")) {
            throw new IllegalArgumentException("\"" + key + "\" has to name a column like \"C\","
                    + " but was \"" + at + "\".");
        }
        int index = CellReference.convertColStringToIndex(cleaned);
        if (index >= EXCEL_MAX_COLUMNS) {
            throw new IllegalArgumentException("\"" + at + "\" is past Excel's last column.");
        }
        return index;
    }

    /**
     * The 0-based first and last row named either by a row range — {@code "5:8"}, {@code "5"}, or a
     * block like {@code "A5:C8"} whose rows are what a row-wise request means by it — or by
     * {@code at} plus {@code count}. Shared so that a row range means the same thing to every action
     * that takes one.
     */
    public static int[] rowSpan(String range, Integer at, Integer count) {
        if (range != null && !range.isBlank()) {
            String cleaned = range.substring(range.lastIndexOf('!') + 1).replace("$", "").trim();
            String[] parts = cleaned.split(":", 2);
            int first = row(rowNumber(parts[0], range), "range");
            int last = parts.length == 1 ? first : row(rowNumber(parts[1], range), "range");
            return new int[]{Math.min(first, last), Math.max(first, last)};
        }
        int first = row(at, "at");
        return new int[]{first, first + count(count, false) - 1};
    }

    private static int rowNumber(String part, String raw) {
        String digits = part.replaceAll("[A-Za-z]", "").trim();
        if (digits.isEmpty()) {
            throw new IllegalArgumentException("\"" + raw + "\" does not name rows — use \"5:8\","
                    + " or \"at\" with \"count\".");
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException _) {
            throw new IllegalArgumentException("\"" + raw + "\" does not name rows — use \"5:8\","
                    + " or \"at\" with \"count\".");
        }
    }

    /** How many rows or columns to work on; absent means one. */
    public static int count(Integer requested, boolean inserting) {
        if (requested == null) {
            return 1;
        }
        if (requested < 1) {
            throw new IllegalArgumentException("\"count\" has to be at least 1, but was " + requested
                    + ".");
        }
        if (inserting && requested > MAX_INSERT) {
            throw new IllegalArgumentException("\"count\" is capped at " + MAX_INSERT
                    + " for one step, but was " + requested + " — insert what the sheet needs, or"
                    + " split the step.");
        }
        return requested;
    }

    /** The rightmost column holding anything, or -1 for an empty sheet. */
    public static int lastUsedColumn(XSSFSheet sheet) {
        int last = -1;
        for (org.apache.poi.ss.usermodel.Row row : sheet) {
            last = Math.max(last, row.getLastCellNum() - 1);
        }
        return last;
    }

    /** {@code "A"}, {@code "AB"} — how a column is named back to the user. */
    public static String columnName(int index) {
        return CellReference.convertNumToColString(index);
    }
}
