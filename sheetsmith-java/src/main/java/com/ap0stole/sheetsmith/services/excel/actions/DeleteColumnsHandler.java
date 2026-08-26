package com.ap0stole.sheetsmith.services.excel.actions;

import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.FormulaErrorScanner;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.ap0stole.sheetsmith.services.excel.model.ColumnShiftConfig;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Removes columns and closes the gap sideways.
 * <p>
 * The column a user names is the one that disappears — {@code "C:E"} deletes three columns and D
 * becomes the old F. Like DELETE_ROWS it is a removal followed by a shift, and like DELETE_ROWS it
 * refuses to pick its own target: the cost of guessing wrong is a column of data gone.
 * <p>
 * The one thing it cannot fix is a chart: a series pointing at the deleted column is left naming a
 * range that has moved under it. The step reports the broken formulas it can see, but a chart holds
 * its ranges outside the formula table, so a plan that deletes a charted column should redraw the
 * chart afterwards.
 */
@Slf4j
@Component
public class DeleteColumnsHandler implements ActionHandler {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getType() {
        return "DELETE_COLUMNS";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws Exception {
        ColumnShiftConfig cfg = mapper.convertValue(properties, ColumnShiftConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        int[] span = span(cfg);
        int first = span[0];
        int last = span[1];

        int lastColumn = StructureShift.lastUsedColumn(sheet);
        if (lastColumn < 0 || first > lastColumn) {
            return "there is nothing in column " + StructureShift.columnName(first)
                    + " or beyond, so there was nothing to delete";
        }
        int clamped = Math.min(last, lastColumn);
        int count = clamped - first + 1;

        List<FormulaErrorScanner.CellError> before = StructureShift.formulaErrors(workbook);

        // The cells go first: shiftColumns moves what is to the right of the hole, it does not empty
        // the hole, so without this the last column's contents would survive twice.
        for (Row row : sheet) {
            for (int c = first; c <= clamped; c++) {
                Cell cell = row.getCell(c);
                if (cell != null) {
                    row.removeCell(cell);
                }
            }
        }
        if (clamped < lastColumn) {
            sheet.shiftColumns(clamped + 1, lastColumn, -count);
        }

        log.info("DELETE_COLUMNS removed columns {}-{} of '{}'",
                StructureShift.columnName(first), StructureShift.columnName(clamped), sheet.getSheetName());

        String where = count == 1 ? "at column " + StructureShift.columnName(first)
                : "from column " + StructureShift.columnName(first) + " to "
                + StructureShift.columnName(clamped);
        return StructureShift.report("deleted", "column", count, where, workbook, before);
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String at = ActionDescriptions.text(properties, "at");
        String range = ActionDescriptions.text(properties, "range");
        Integer count = ActionDescriptions.integer(properties, "count");

        String what;
        if (range != null && !range.isBlank()) {
            what = "columns " + range.trim().toUpperCase();
        } else if (at == null || at.isBlank()) {
            what = "the columns";
        } else {
            int columns = count == null || count < 1 ? 1 : count;
            what = columns == 1 ? "column " + at.trim().toUpperCase()
                    : columns + " columns from " + at.trim().toUpperCase();
        }
        return ActionDescriptions.verb(tense, "Delete", "Deleted") + " " + what
                + ActionDescriptions.sheetSuffix(properties);
    }

    /** The 0-based first and last column to remove, from either {@code range} or {@code at} + count. */
    private int[] span(ColumnShiftConfig cfg) {
        String range = cfg.getRange();
        if (range != null && !range.isBlank()) {
            String cleaned = range.substring(range.lastIndexOf('!') + 1).replace("$", "").trim();
            String[] parts = cleaned.split(":", 2);
            int first = StructureShift.column(letters(parts[0], range), "range");
            int last = parts.length == 1 ? first : StructureShift.column(letters(parts[1], range), "range");
            return new int[]{Math.min(first, last), Math.max(first, last)};
        }
        int first = StructureShift.column(cfg.getAt(), "at");
        return new int[]{first, first + StructureShift.count(cfg.getCount(), false) - 1};
    }

    /** {@code "C"} out of {@code "C"} or {@code "C2"} — a block's columns are what it names here. */
    private String letters(String part, String raw) {
        String stripped = part.replaceAll("\\d", "").trim();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException("\"" + raw + "\" does not name columns — use \"C:E\","
                    + " or \"at\" with \"count\".");
        }
        return stripped;
    }
}
