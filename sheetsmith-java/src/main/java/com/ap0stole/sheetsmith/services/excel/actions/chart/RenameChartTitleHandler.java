package com.ap0stole.sheetsmith.services.excel.actions.chart;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.chart.RenameChartTitleConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.io.IOException;

@Component
public class RenameChartTitleHandler implements ActionHandler {

    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Override
    public String getType() {
        return "RENAME_CHART_TITLE";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws IOException {
        RenameChartTitleConfig cfg = mapper.convertValue(properties, RenameChartTitleConfig.class);
        new ChartHandler().renameTitle(workbook, cfg);

        return null;
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String title = ActionDescriptions.text(properties, "newTitle");
        return ActionDescriptions.verb(tense, "Rename", "Renamed") + " the chart title"
                + (title == null ? "" : " to " + ActionDescriptions.quoted(title))
                + ActionDescriptions.sheetSuffix(properties);
    }
}
