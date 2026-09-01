package com.ap0stole.sheetsmith.services.excel.actions.structure;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.structure.RowShiftConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.FormulaErrorScanner;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.io.IOException;

/**
 * Makes room: everything from the named row down moves further down.
 * <p>
 * The rows arrive empty, deliberately. Excel's own insert copies the formatting of the row above,
 * which is helpful in a spreadsheet where a human is watching and a trap in a plan where the next
 * step writes into them — a header's fill and bold inherited by a blank data row is a change nobody
 * asked for and no step reports. FORMAT_CELLS can style them when that is actually wanted.
 * <p>
 * Inserting past the last row of the sheet is not an error but nothing to do either: the space is
 * already empty, and there is nothing below to push down.
 */
@Slf4j
@Component
public class InsertRowsHandler implements ActionHandler {

    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Override
    public String getType() {
        return "INSERT_ROWS";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws IOException {
        RowShiftConfig cfg = mapper.convertValue(properties, RowShiftConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        int at = StructureShift.row(cfg.getAt(), "at");
        int count = StructureShift.count(cfg.getCount(), true);

        int lastRow = sheet.getLastRowNum();
        if (sheet.getPhysicalNumberOfRows() == 0 || at > lastRow) {
            log.info("INSERT_ROWS at row {} of '{}' had nothing below it to move", at + 1, sheet.getSheetName());
            return "there is nothing at or below row " + (at + 1) + ", so the space was already empty";
        }
        if (lastRow + count >= StructureShift.EXCEL_MAX_ROWS) {
            throw new IllegalArgumentException("Inserting " + count + " row(s) would push content"
                    + " past Excel's last row (" + StructureShift.EXCEL_MAX_ROWS + ").");
        }

        List<FormulaErrorScanner.CellError> before = StructureShift.formulaErrors(workbook);
        // copyRowHeight true keeps a moved row the height it had; resetOriginalRowHeight false stops
        // POI resetting the vacated rows to the sheet default, which is the height they should keep.
        sheet.shiftRows(at, lastRow, count, true, false);

        log.info("INSERT_ROWS added {} row(s) at row {} of '{}'", count, at + 1, sheet.getSheetName());
        return StructureShift.report("inserted", "row", count, "at row " + (at + 1), workbook, before);
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        Integer at = ActionDescriptions.integer(properties, "at");
        Integer count = ActionDescriptions.integer(properties, "count");
        int rows = count == null || count < 1 ? 1 : count;

        return ActionDescriptions.verb(tense, "Insert", "Inserted") + " "
                + (rows == 1 ? "a row" : rows + " rows")
                + (at == null ? "" : " above row " + at)
                + ActionDescriptions.sheetSuffix(properties);
    }
}
