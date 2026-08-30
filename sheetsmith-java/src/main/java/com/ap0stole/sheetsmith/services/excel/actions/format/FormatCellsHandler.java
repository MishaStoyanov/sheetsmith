package com.ap0stole.sheetsmith.services.excel.actions.format;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.format.StyleConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.io.IOException;

@Component
public class FormatCellsHandler implements ActionHandler {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getType() {
        return "FORMAT_CELLS";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws IOException {
        StyleConfig cfg = mapper.convertValue(properties, StyleConfig.class);
        new StyleHandler().execute(workbook, cfg);

        return null;
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String range = ActionDescriptions.range(properties, "range");
        String styling = ActionDescriptions.styling(properties);

        String text = ActionDescriptions.verb(tense, "Format", "Formatted")
                + " " + (range == null ? "the cells" : range)
                + ActionDescriptions.sheetSuffix(properties);
        return styling.isEmpty() ? text : text + " — " + styling;
    }
}
