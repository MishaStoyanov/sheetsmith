package com.ap0stole.sheetsmith.services.excel.actions.cell;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.cell.RenameColumnConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.io.IOException;

@Slf4j
@Component
public class RenameColumnHandler implements ActionHandler {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getType() {
        return "RENAME_COLUMN";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws IOException {
        RenameColumnConfig cfg = mapper.convertValue(properties, RenameColumnConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        CellReference ref = new CellReference(cfg.getCell());

        Row row = sheet.getRow(ref.getRow());
        if (row == null) row = sheet.createRow(ref.getRow());
        Cell cell = row.getCell(ref.getCol());
        if (cell == null) cell = row.createCell(ref.getCol());

        cell.setCellValue(cfg.getNewName());
        log.info("Renamed column header at {} to '{}'", cfg.getCell(), cfg.getNewName());

        return null;
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String cell = ActionDescriptions.range(properties, "cell");
        String newName = ActionDescriptions.text(properties, "newName");

        StringBuilder text = new StringBuilder(ActionDescriptions.verb(tense, "Rename", "Renamed"))
                .append(" the column");
        if (cell != null) {
            text.append(" in ").append(cell);
        }
        if (newName != null) {
            text.append(" to ").append(ActionDescriptions.quoted(newName));
        }
        return text.append(ActionDescriptions.sheetSuffix(properties)).toString();
    }
}
