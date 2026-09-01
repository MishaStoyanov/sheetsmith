package com.ap0stole.sheetsmith.services.excel.actions.format;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.format.DataBarsConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.CellStyles;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.ConditionalFormattingRule;
import org.apache.poi.ss.usermodel.ConditionalFormattingThreshold.RangeType;
import org.apache.poi.ss.usermodel.DataBarFormatting;
import org.apache.poi.ss.usermodel.SheetConditionalFormatting;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.io.IOException;

/**
 * Draws a bar inside each cell, as long as the value is large — a column read at a glance without
 * leaving the table for a chart, and the one way to compare 500 rows that CREATE_CHART cannot.
 * <p>
 * Like COLOR_SCALE the bar is measured against the range's own smallest and largest value, so no
 * bound has to be guessed and the bars stay honest when the numbers change.
 */
@Slf4j
@Component
public class DataBarsHandler implements ActionHandler {

    /** Sky blue, from the prompt's palette — dark enough to read, light enough to type over. */
    private static final String DEFAULT_COLOR = "#0EA5E9";

    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Override
    public String getType() {
        return "DATA_BARS";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws IOException {
        DataBarsConfig cfg = mapper.convertValue(properties, DataBarsConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        CellRangeAddress area = CellStyles.area(cfg.getRange(), "range");
        XSSFColor color = CellStyles.color(
                cfg.getColor() == null || cfg.getColor().isBlank() ? DEFAULT_COLOR : cfg.getColor(), "color");

        SheetConditionalFormatting scf = sheet.getSheetConditionalFormatting();
        ConditionalFormattingRule rule = scf.createConditionalFormattingRule(color);
        DataBarFormatting bar = rule.getDataBarFormatting();
        bar.getMinThreshold().setRangeType(RangeType.MIN);
        bar.getMaxThreshold().setRangeType(RangeType.MAX);
        // POI names it the other way round: "icon only" is Excel's "show bar only", the box a user
        // ticks to keep the bar and hide the number it is drawn from.
        bar.setIconOnly(Boolean.FALSE.equals(cfg.getShowValue()));

        scf.addConditionalFormatting(new CellRangeAddress[]{area}, rule);

        log.info("DATA_BARS drew bars across {} on '{}'", area.formatAsString(), sheet.getSheetName());
        return ValueScale.detail(ValueScale.coverage(sheet, area));
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String range = ActionDescriptions.range(properties, "range");
        String color = ActionDescriptions.colorWords(ActionDescriptions.text(properties, "color"), DEFAULT_COLOR);
        boolean hidden = !ActionDescriptions.flag(properties, "showValue", true);

        return ActionDescriptions.verb(tense, "Draw", "Drew") + " " + color + " bars in "
                + (range == null ? "the cells" : range) + " in proportion to each value"
                + (hidden ? ", hiding the numbers themselves" : "")
                + ActionDescriptions.sheetSuffix(properties);
    }
}
