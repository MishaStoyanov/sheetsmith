package com.ap0stole.sheetsmith.services.excel.actions.chart;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.chart.RenameChartAxisConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.io.IOException;

@Component
public class RenameChartAxisHandler implements ActionHandler {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getType() {
        return "RENAME_CHART_AXIS";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws IOException {
        RenameChartAxisConfig cfg = mapper.convertValue(properties, RenameChartAxisConfig.class);
        new ChartHandler().renameAxis(workbook, cfg);

        return null;
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String axis = ActionDescriptions.text(properties, "axis");
        String which = axis == null ? "axis" : switch (axis.trim().toLowerCase(Locale.ROOT)) {
            case "value", "y", "yaxis", "y-axis" -> "y-axis";
            case "category", "x", "xaxis", "x-axis" -> "x-axis";
            default -> "axis";
        };
        String title = ActionDescriptions.text(properties, "newTitle");

        return ActionDescriptions.verb(tense, "Label", "Labelled") + " the " + which
                + (title == null ? "" : " " + ActionDescriptions.quoted(title))
                + ActionDescriptions.sheetSuffix(properties);
    }
}
