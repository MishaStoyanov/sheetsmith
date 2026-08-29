package com.ap0stole.sheetsmith.services.excel.actions.format;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.format.ConditionalFormatConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class ConditionalFormattingHandler implements ActionHandler {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getType() {
        return "CONDITIONAL_FORMATTING";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws Exception {
        ConditionalFormatConfig cfg = mapper.convertValue(properties, ConditionalFormatConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());

        SheetConditionalFormatting scf = sheet.getSheetConditionalFormatting();

        ConditionalFormattingRule rule = buildRule(workbook, scf, cfg);

        CellRangeAddress[] ranges = {CellRangeAddress.valueOf(cfg.getRange())};
        scf.addConditionalFormatting(ranges, rule);

        log.info("Conditional formatting applied to range {}", cfg.getRange());

        return null;
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String range = ActionDescriptions.range(properties, "range");
        String operator = ActionDescriptions.comparison(ActionDescriptions.text(properties, "operator"));
        String value = ActionDescriptions.text(properties, "value");
        String target = range == null ? "cells" : "cells in " + range;

        String text = ActionDescriptions.verb(tense, "Highlight", "Highlighted") + " "
                + ((operator == null || value == null)
                ? "non-empty " + target
                : target + " where the value is " + operator + " " + value);
        return text + ActionDescriptions.sheetSuffix(properties);
    }

    private ConditionalFormattingRule buildRule(XSSFWorkbook workbook,
                                                SheetConditionalFormatting scf,
                                                ConditionalFormatConfig cfg) {
        ConditionalFormattingRule rule;

        String operator = cfg.getOperator();
        String value = cfg.getValue();

        if (operator != null && value != null) {
            byte op = mapOperator(operator);
            rule = scf.createConditionalFormattingRule(op, value);
        } else {
            // formula-based fallback: highlight non-empty cells
            rule = scf.createConditionalFormattingRule("NOT(ISBLANK(" + cfg.getRange().split(":")[0] + "))");
        }

        PatternFormatting pattern = rule.createPatternFormatting();
        if (cfg.getBackgroundColor() != null) {
            pattern.setFillBackgroundColor(parseColor(workbook, cfg.getBackgroundColor()));
            pattern.setFillPattern(PatternFormatting.SOLID_FOREGROUND);
        }

        FontFormatting font = rule.createFontFormatting();
        if (Boolean.TRUE.equals(cfg.getBold())) {
            font.setFontStyle(false, true);
        }

        return rule;
    }

    private byte mapOperator(String op) {
        return switch (op.toUpperCase()) {
            case "GT", ">" -> ComparisonOperator.GT;
            case "GE", ">=" -> ComparisonOperator.GE;
            case "LT", "<" -> ComparisonOperator.LT;
            case "LE", "<=" -> ComparisonOperator.LE;
            case "EQ", "=" -> ComparisonOperator.EQUAL;
            case "NE", "!=" -> ComparisonOperator.NOT_EQUAL;
            default -> ComparisonOperator.GT;
        };
    }

    private XSSFColor parseColor(XSSFWorkbook workbook, String hex) {
        String clean = hex.replace("#", "");
        byte[] rgb = new byte[]{
                (byte) Integer.parseInt(clean.substring(0, 2), 16),
                (byte) Integer.parseInt(clean.substring(2, 4), 16),
                (byte) Integer.parseInt(clean.substring(4, 6), 16)
        };
        return new XSSFColor(rgb, workbook.getStylesSource().getIndexedColors());
    }
}
