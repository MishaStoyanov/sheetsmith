package com.ap0stole.sheetsmith.services.excel.actions.sheet;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.sheet.SheetConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class AddSheetHandler implements ActionHandler {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getType() {
        return "ADD_SHEET";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws Exception {
        SheetConfig cfg = mapper.convertValue(properties, SheetConfig.class);
        String name = cfg.getName();
        if (name == null || name.isBlank()) {
            name = "NewSheet_" + (workbook.getNumberOfSheets() + 1);
        }
        name = WorkbookUtil.createSafeSheetName(name);
        if (workbook.getSheet(name) != null) {
            log.warn("Sheet '{}' already exists, skipping", name);
            return "a sheet named \"" + name + "\" was already there, so nothing was added";
        }
        workbook.createSheet(name);
        log.info("Created sheet '{}'", name);

        return null;
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String name = ActionDescriptions.text(properties, "name");
        return ActionDescriptions.verb(tense, "Add", "Added") + " a sheet"
                + (name == null ? "" : " named " + ActionDescriptions.quoted(name));
    }
}
