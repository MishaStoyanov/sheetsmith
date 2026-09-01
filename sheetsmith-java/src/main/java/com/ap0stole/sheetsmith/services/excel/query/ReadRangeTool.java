package com.ap0stole.sheetsmith.services.excel.query;

import com.ap0stole.sheetsmith.configs.ConditionalOnChatEnabled;
import com.ap0stole.sheetsmith.configs.ChatConfig;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.ap0stole.sheetsmith.services.excel.query.model.ReadRangeConfig;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnChatEnabled
public class ReadRangeTool implements QueryTool {

    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final ChatConfig chatConfig;

    @Override
    public String getType() {
        return "READ_RANGE";
    }

    @Override
    public String promptSpec() {
        return """
                READ_RANGE
                   Keys: "range" (e.g. "A1:C10") — returns the raw cell values, formulas as their results
                   Optional: "sheetName", "sheetIndex"
                   Limited to %d cells per call; for anything bigger use AGGREGATE or FIND_ROWS.
                   Example: {"tool": "READ_RANGE", "args": {"range": "A1:C10", "sheetName": "Sales"}}"""
                .formatted(chatConfig.getMaxCells());
    }

    @Override
    public QueryResult execute(XSSFWorkbook workbook, Map<String, Object> properties) {
        ReadRangeConfig cfg = mapper.convertValue(properties, ReadRangeConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        CellRangeAddress range = CellRangeAddress.valueOf(cfg.getRange());

        int firstRow = range.getFirstRow();
        int lastRow = range.getLastRow();
        int firstCol = range.getFirstColumn();
        int lastCol = range.getLastColumn();

        long cells = (long) (lastRow - firstRow + 1) * (lastCol - firstCol + 1);
        if (cells > chatConfig.getMaxCells()) {
            throw new IllegalArgumentException("Range " + range.formatAsString() + " covers " + cells
                    + " cells, limit is " + chatConfig.getMaxCells()
                    + " — request a smaller range or use AGGREGATE instead.");
        }

        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        List<List<Object>> values = new ArrayList<>();
        for (int r = firstRow; r <= lastRow; r++) {
            List<Object> row = new ArrayList<>();
            for (int c = firstCol; c <= lastCol; c++) {
                row.add(QuerySupport.cellValue(sheet, r, c, evaluator));
            }
            values.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sheet", sheet.getSheetName());
        data.put("range", range.formatAsString());
        data.put("values", values);

        log.info("READ_RANGE {} on sheet '{}' returned {} cells", range.formatAsString(), sheet.getSheetName(), cells);

        String summary = "Read %s on %s — %d row(s) x %d column(s)".formatted(
                range.formatAsString(), sheet.getSheetName(), values.size(), lastCol - firstCol + 1);
        return new QueryResult(QuerySupport.clip(summary, 120), data);
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String range = QuerySupport.propString(properties, "range");
        return QuerySupport.verb(tense, "Read", "Read") + " " + (range == null ? "a range" : range)
                + QuerySupport.sheetSuffix(properties);
    }
}
