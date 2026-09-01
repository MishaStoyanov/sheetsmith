package com.ap0stole.sheetsmith.services.excel.actions.cell;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.cell.ClearCellsConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.io.IOException;

@Slf4j
@Component
public class ClearCellsHandler implements ActionHandler {

    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Override
    public String getType() {
        return "CLEAR_CELLS";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws IOException {
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
