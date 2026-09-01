package com.ap0stole.sheetsmith.services.excel.query;

import com.ap0stole.sheetsmith.configs.ConditionalOnChatEnabled;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.ap0stole.sheetsmith.services.excel.query.model.DescribeColumnConfig;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@ConditionalOnChatEnabled
public class DescribeColumnTool implements QueryTool {

    private static final int SAMPLE_SIZE = 10;

    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Override
    public String getType() {
        return "DESCRIBE_COLUMN";
    }

    @Override
    public String promptSpec() {
        return """
                DESCRIBE_COLUMN
                   Keys: "range" (data rows only, e.g. "A2:D200"), "columnIndex" (0-based absolute sheet column)
                   Optional: "sheetName", "sheetIndex"
                   Returns count of non-blank values, blanks, distinct count, type (numeric|text|mixed|empty),
                   min/max for numbers and up to %d sample values. Use it before AGGREGATE when unsure what a
                   column holds.
                   Example: {"tool": "DESCRIBE_COLUMN", "args": {"range": "A2:D200", "columnIndex": 1}}"""
                .formatted(SAMPLE_SIZE);
    }

    @Override
    public QueryResult execute(XSSFWorkbook workbook, Map<String, Object> properties) {
        DescribeColumnConfig cfg = mapper.convertValue(properties, DescribeColumnConfig.class);
        if (cfg.getColumnIndex() == null) {
            throw new IllegalArgumentException("\"columnIndex\" is required (0-based absolute sheet column).");
        }

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        CellRangeAddress range = CellRangeAddress.valueOf(cfg.getRange());
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

        int column = cfg.getColumnIndex();
        Tally tally = read(sheet, range, column, evaluator);
        int count = tally.count();
        Set<String> distinct = tally.distinct();
        String type = tally.type();

        Map<String, Object> data = tally.asData();

        log.info("DESCRIBE_COLUMN {} over {} — {} value(s), {} distinct, type {}",
                QuerySupport.colName(column), range.formatAsString(), count, distinct.size(), type);

        StringBuilder summary = new StringBuilder("Column %s — %d value(s) (%s), %d distinct"
                .formatted(QuerySupport.colName(column), count, type, distinct.size()));
        if (tally.hasNumericBounds()) {
            summary.append(", min ").append(QuerySupport.fmt(QuerySupport.round(tally.min())))
                    .append(", max ").append(QuerySupport.fmt(QuerySupport.round(tally.max())));
        }
        return new QueryResult(QuerySupport.clip(summary.toString(), 120), data);
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String range = QuerySupport.propString(properties, "range");
        Integer column = QuerySupport.propInt(properties, "columnIndex");
        return QuerySupport.verb(tense, "Describe", "Described") + " column " + QuerySupport.colName(column)
                + (range == null ? "" : " over " + range) + QuerySupport.sheetSuffix(properties);
    }

    /**
     * One pass over the column, and everything that pass learned.
     * <p>
     * A record rather than seven locals threaded through the method that reports them: the reading
     * and the describing are two jobs, and only the first one needs to know that a blank is not a
     * value and that the samples stop at a limit.
     */
    private record Tally(int count, int blanks, int numerics, Double min, Double max,
                         Set<String> distinct, List<Object> samples) {

        /** What the column holds, in one word. */
        String type() {
            if (count == 0) {
                return "empty";
            }
            if (numerics == count) {
                return "numeric";
            }
            return numerics == 0 ? "text" : "mixed";
        }

        /** Bounds are worth printing only where some of the values were numbers. */
        boolean hasNumericBounds() {
            return numerics > 0 && min != null;
        }

        Map<String, Object> asData() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("count", count);
            data.put("blanks", blanks);
            data.put("distinct", distinct.size());
            data.put("type", type());
            data.put("min", hasNumericBounds() ? QuerySupport.round(min) : null);
            data.put("max", hasNumericBounds() ? QuerySupport.round(max) : null);
            data.put("sampleValues", samples);
            return data;
        }
    }

    /** Reads the column once: what is there, how much of it, and a few examples. */
    private Tally read(XSSFSheet sheet, CellRangeAddress range, int column, FormulaEvaluator evaluator) {
        int count = 0;
        int blanks = 0;
        int numerics = 0;
        Double min = null;
        Double max = null;
        Set<String> distinct = new LinkedHashSet<>();
        List<Object> samples = new ArrayList<>();

        for (int r = range.getFirstRow(); r <= range.getLastRow(); r++) {
            Object value = QuerySupport.cellValue(sheet, r, column, evaluator);
            if (QuerySupport.isBlank(value)) {
                blanks++;
                continue;
            }
            count++;
            if (distinct.add(QuerySupport.asText(value)) && samples.size() < SAMPLE_SIZE) {
                samples.add(value);
            }
            Double number = QuerySupport.asNumber(value);
            if (number != null) {
                numerics++;
                min = min == null ? number : Math.min(min, number);
                max = max == null ? number : Math.max(max, number);
            }
        }
        return new Tally(count, blanks, numerics, min, max, distinct, samples);
    }
}
