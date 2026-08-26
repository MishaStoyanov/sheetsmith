package com.ap0stole.sheetsmith.services.excel.actions;

import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.FormulaErrorScanner;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.ap0stole.sheetsmith.services.excel.model.RowShiftConfig;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Removes rows and closes the gap — the counterpart to INSERT_ROWS, and the more dangerous half.
 * <p>
 * Deleting is two operations POI keeps separate: the rows themselves are removed, and then
 * everything below is shifted up over the hole. Doing only the first leaves a sheet with a gap and
 * every formula below still pointing where it always did; doing only the second overwrites.
 * <p>
 * The range is clamped to rows that exist, so "delete rows 5 to 500" on a 20-row sheet removes what
 * is there rather than failing, and says how many that was. What it will not do is guess: with
 * neither {@code at} nor {@code range} it refuses, because a deletion that picks its own target is
 * the one mistake here that cannot be undone from inside the step.
 */
@Slf4j
@Component
public class DeleteRowsHandler implements ActionHandler {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getType() {
        return "DELETE_ROWS";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws Exception {
        RowShiftConfig cfg = mapper.convertValue(properties, RowShiftConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        int[] span = StructureShift.rowSpan(cfg.getRange(), cfg.getAt(), cfg.getCount());
        int first = span[0];
        int last = span[1];

        if (sheet.getPhysicalNumberOfRows() == 0) {
            return "the sheet is empty, so there was nothing to delete";
        }
        int lastRow = sheet.getLastRowNum();
        if (first > lastRow) {
            return "the sheet ends at row " + (lastRow + 1) + ", so there was nothing to delete";
        }
        // Asking past the end is a miscount, not a failure: delete what is there and say how much.
        int clamped = Math.min(last, lastRow);
        int count = clamped - first + 1;

        List<FormulaErrorScanner.CellError> before = StructureShift.formulaErrors(workbook);

        for (int r = first; r <= clamped; r++) {
            Row row = sheet.getRow(r);
            if (row != null) {
                sheet.removeRow(row);
            }
        }
        // Only what is below the hole moves, and only when there is something below it.
        if (clamped < lastRow) {
            sheet.shiftRows(clamped + 1, lastRow, -count, true, false);
        }

        log.info("DELETE_ROWS removed rows {}-{} of '{}' ({} asked for)",
                first + 1, clamped + 1, sheet.getSheetName(), last - first + 1);

        String where = count == 1 ? "at row " + (first + 1)
                : "from row " + (first + 1) + " to " + (clamped + 1);
        String report = StructureShift.report("deleted", "row", count, where, workbook, before);
        return clamped < last
                ? report + " (the sheet ended at row " + (lastRow + 1) + ")"
                : report;
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        return ActionDescriptions.verb(tense, "Delete", "Deleted") + " "
                + ActionDescriptions.rowSpan(properties)
                + ActionDescriptions.sheetSuffix(properties);
    }

}
