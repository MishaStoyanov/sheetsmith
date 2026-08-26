package com.ap0stole.sheetsmith.services.excel.actions;

import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.model.RenameSheetConfig;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class RenameSheetHandler implements ActionHandler {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getType() {
        return "RENAME_SHEET";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws Exception {
        RenameSheetConfig cfg = mapper.convertValue(properties, RenameSheetConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        int index = workbook.getSheetIndex(sheet);
        workbook.setSheetName(index, cfg.getNewName());
        log.info("Renamed sheet {} to '{}'", index, cfg.getNewName());

        return null;
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String sheet = ActionDescriptions.text(properties, "sheetName");
        String newName = ActionDescriptions.text(properties, "newName");

        return ActionDescriptions.verb(tense, "Rename", "Renamed") + " the sheet"
                + (sheet == null ? "" : " " + ActionDescriptions.quoted(sheet))
                + (newName == null ? "" : " to " + ActionDescriptions.quoted(newName));
    }
}
