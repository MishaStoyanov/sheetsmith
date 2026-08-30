package com.ap0stole.sheetsmith.services.excel.actions.formula;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.formula.FormulaConfig;
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
public class AddFormulaHandler implements ActionHandler {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getType() {
        return "ADD_FORMULA";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws IOException {
        FormulaConfig cfg = mapper.convertValue(properties, FormulaConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());

        CellReference ref = new CellReference(cfg.getCell());
        Row row = sheet.getRow(ref.getRow());
        if (row == null) row = sheet.createRow(ref.getRow());
        Cell cell = row.getCell(ref.getCol());
        if (cell == null) cell = row.createCell(ref.getCol());

        String formula = cfg.getFormula().replaceAll("^=+", "");
        cell.setCellFormula(formula);
        log.info("Formula '{}' set at cell {}", cfg.getFormula(), cfg.getCell());

        if (cfg.getLabel() != null && !cfg.getLabel().isBlank() && ref.getCol() > 0) {
            Cell labelCell = row.getCell(ref.getCol() - 1);
            if (labelCell == null) labelCell = row.createCell(ref.getCol() - 1);
            labelCell.setCellValue(cfg.getLabel());
            log.info("Label '{}' set at cell adjacent to {}", cfg.getLabel(), cfg.getCell());
        }

        return null;
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String formula = ActionDescriptions.formula(properties, "formula");
        String cell = ActionDescriptions.range(properties, "cell");
        String label = ActionDescriptions.text(properties, "label");

        StringBuilder text = new StringBuilder(ActionDescriptions.verb(tense, "Add", "Added"))
                .append(' ')
                .append(formula != null ? formula : "a formula");
        if (cell != null) {
            text.append(" in ").append(cell);
        }
        if (label != null) {
            text.append(", labelled ").append(ActionDescriptions.quoted(label));
        }
        return text.append(ActionDescriptions.sheetSuffix(properties)).toString();
    }
}
