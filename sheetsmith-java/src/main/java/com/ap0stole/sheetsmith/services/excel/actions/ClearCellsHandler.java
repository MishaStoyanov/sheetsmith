package com.ap0stole.sheetsmith.services.excel.actions;

import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.model.ClearCellsConfig;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class ClearCellsHandler implements ActionHandler {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getType() {
        return "CLEAR_CELLS";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws Exception {
        ClearCellsConfig cfg = mapper.convertValue(properties, ClearCellsConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        CellRangeAddress range = CellRangeAddress.valueOf(cfg.getRange());

        for (int r = range.getFirstRow(); r <= range.getLastRow(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (int c = range.getFirstColumn(); c <= range.getLastColumn(); c++) {
                Cell cell = row.getCell(c);
                if (cell != null) {
                    row.removeCell(cell);
                }
            }
        }
        log.info("Cleared cells {}", cfg.getRange());

        return null;
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String range = ActionDescriptions.range(properties, "range");
        return ActionDescriptions.verb(tense, "Clear", "Cleared") + " " + (range == null ? "the cells" : range)
                + ActionDescriptions.sheetSuffix(properties);
    }
}
