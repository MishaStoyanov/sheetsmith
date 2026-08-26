package com.ap0stole.sheetsmith.services.excel.query;

import com.ap0stole.sheetsmith.configs.ConditionalOnChatEnabled;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.ap0stole.sheetsmith.services.excel.query.model.EvalFormulaConfig;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnChatEnabled
public class EvalFormulaTool implements QueryTool {

    /** Far enough below the data that no styling, merge or table region can reach it. */
    private static final int SCRATCH_ROW_GAP = 5;

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getType() {
        return "EVAL_FORMULA";
    }

    @Override
    public String promptSpec() {
        return """
                EVAL_FORMULA
                   Keys: "formula" (any Excel formula WITHOUT the leading "=", references resolved against the
                   target sheet, e.g. "SUMIF(B2:B20,\\">100\\",C2:C20)")
                   Optional: "sheetName", "sheetIndex"
                   The strongest tool: SUMIF, COUNTIF, AVERAGEIF, VLOOKUP, INDEX/MATCH, MAX, SUMPRODUCT… all work
                   and only the single result comes back. Prefer it over reading rows whenever the question is a
                   calculation. The sheet is not modified.
                   Example: {"tool": "EVAL_FORMULA", "args": {"formula": "SUMIF(B2:B20,\\">100\\",C2:C20)", "sheetName": "Sales"}}""";
    }

    @Override
    public QueryResult execute(XSSFWorkbook workbook, Map<String, Object> properties) {
        EvalFormulaConfig cfg = mapper.convertValue(properties, EvalFormulaConfig.class);
        if (cfg.getFormula() == null || cfg.getFormula().isBlank()) {
            throw new IllegalArgumentException("\"formula\" is required (an Excel formula without the leading \"=\").");
        }

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        String formula = cfg.getFormula().trim().replaceAll("^=+", "");

        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        int scratchRowIdx = scratchRowIndex(sheet);
        Row scratchRow = sheet.createRow(scratchRowIdx);
        Map<String, Object> data = new LinkedHashMap<>();
        String rendered;
        try {
            Cell scratch = scratchRow.createCell(0);
            scratch.setCellFormula(formula);
            CellValue evaluated = evaluator.evaluate(scratch);

            data.put("formula", formula);
            data.put("value", value(evaluated));
            data.put("type", type(evaluated));
            rendered = QuerySupport.fmt(data.get("value"));
        } finally {
            // The same workbook may still be saved later in this turn — leave no residue behind.
            sheet.removeRow(scratchRow);
            evaluator.clearAllCachedResultValues();
        }

        log.info("EVAL_FORMULA '{}' on sheet '{}' = {}", formula, sheet.getSheetName(), rendered);
        return new QueryResult(QuerySupport.clip("=" + formula + " = " + rendered, 120), data);
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String verb = QuerySupport.verb(tense, "Evaluate", "Evaluated");
        String formula = QuerySupport.propString(properties, "formula");
        if (formula == null) return verb + " a formula";
        String clean = formula.trim().replaceAll("^=+", "");
        return verb + " " + QuerySupport.clip("=" + clean, 120) + QuerySupport.sheetSuffix(properties);
    }

    private int scratchRowIndex(XSSFSheet sheet) {
        int last = Math.max(sheet.getLastRowNum(), 0);
        return Math.min(last + SCRATCH_ROW_GAP, SpreadsheetVersion.EXCEL2007.getLastRowIndex());
    }

    private Object value(CellValue evaluated) {
        if (evaluated == null) return null;
        return switch (evaluated.getCellType()) {
            case NUMERIC -> QuerySupport.tidy(evaluated.getNumberValue());
            case STRING -> evaluated.getStringValue();
            case BOOLEAN -> evaluated.getBooleanValue();
            case ERROR -> QuerySupport.errorText(evaluated.getErrorValue());
            default -> null;
        };
    }

    private String type(CellValue evaluated) {
        if (evaluated == null) return "empty";
        return switch (evaluated.getCellType()) {
            case NUMERIC -> "number";
            case STRING -> "text";
            case BOOLEAN -> "boolean";
            case ERROR -> "error";
            default -> "empty";
        };
    }
}
