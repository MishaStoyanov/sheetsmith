package com.ap0stole.sheetsmith.services.excel.actions;

import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.FormulaErrorScanner;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.ap0stole.sheetsmith.services.excel.model.ColumnShiftConfig;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Makes room sideways: the named column and everything right of it move right.
 * <p>
 * The counterpart to INSERT_ROWS and the same contract — the new columns arrive empty, and a column
 * past the last one holding anything is space that is already free. Column widths belong to the
 * sheet rather than to the cells, so a column inserted before a wide one leaves the width where it
 * was; AUTOSIZE_COLUMNS is the step that fixes that once there is something in it to measure.
 */
@Slf4j
@Component
public class InsertColumnsHandler implements ActionHandler {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getType() {
        return "INSERT_COLUMNS";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws Exception {
        ColumnShiftConfig cfg = mapper.convertValue(properties, ColumnShiftConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        int at = StructureShift.column(cfg.getAt() == null ? cfg.getRange() : cfg.getAt(), "at");
        int count = StructureShift.count(cfg.getCount(), true);

        int lastColumn = StructureShift.lastUsedColumn(sheet);
        if (lastColumn < 0 || at > lastColumn) {
            log.info("INSERT_COLUMNS at {} of '{}' had nothing to the right to move",
                    StructureShift.columnName(at), sheet.getSheetName());
            return "there is nothing in or right of column " + StructureShift.columnName(at)
                    + ", so the space was already empty";
        }
        if (lastColumn + count >= StructureShift.EXCEL_MAX_COLUMNS) {
            throw new IllegalArgumentException("Inserting " + count + " column(s) would push content"
                    + " past Excel's last column (" + StructureShift.columnName(StructureShift.EXCEL_MAX_COLUMNS - 1)
                    + ").");
        }

        List<FormulaErrorScanner.CellError> before = StructureShift.formulaErrors(workbook);
        sheet.shiftColumns(at, lastColumn, count);

        log.info("INSERT_COLUMNS added {} column(s) at {} of '{}'",
                count, StructureShift.columnName(at), sheet.getSheetName());
        return StructureShift.report("inserted", "column", count,
                "at column " + StructureShift.columnName(at), workbook, before);
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String at = ActionDescriptions.text(properties, "at");
        Integer count = ActionDescriptions.integer(properties, "count");
        int columns = count == null || count < 1 ? 1 : count;

        return ActionDescriptions.verb(tense, "Insert", "Inserted") + " "
                + (columns == 1 ? "a column" : columns + " columns")
                + (at == null || at.isBlank() ? "" : " before column " + at.trim().toUpperCase())
                + ActionDescriptions.sheetSuffix(properties);
    }
}
