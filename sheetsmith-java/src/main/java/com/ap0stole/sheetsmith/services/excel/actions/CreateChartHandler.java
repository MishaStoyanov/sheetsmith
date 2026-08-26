package com.ap0stole.sheetsmith.services.excel.actions;

import com.ap0stole.sheetsmith.excel_improver.charts.ChartConfig;
import com.ap0stole.sheetsmith.excel_improver.charts.ChartHandler;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

@Component
public class CreateChartHandler implements ActionHandler {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getType() {
        return "CREATE_CHART";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws Exception {
        ChartConfig cfg = mapper.convertValue(properties, ChartConfig.class);
        new ChartHandler().execute(workbook, cfg);

        return null;
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String type = ActionDescriptions.text(properties, "chartType");
        String kind = type == null ? "chart" : switch (type.trim().toLowerCase(Locale.ROOT)) {
            case "barchart", "bar" -> "bar chart";
            case "piechart", "pie" -> "pie chart";
            default -> "chart";
        };
        String title = ActionDescriptions.text(properties, "title");
        String source = ActionDescriptions.range(properties, "sourceRange");

        StringBuilder text = new StringBuilder(ActionDescriptions.verb(tense, "Create", "Created"))
                .append(" a ").append(kind);
        if (title != null) {
            text.append(' ').append(ActionDescriptions.quoted(title));
        }
        if (source != null) {
            text.append(" from ").append(source);
        }
        return text.append(ActionDescriptions.sheetSuffix(properties)).toString();
    }
}
