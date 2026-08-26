package com.ap0stole.sheetsmith.services.excel.query;

import com.ap0stole.sheetsmith.configs.ConditionalOnChatEnabled;
import com.ap0stole.sheetsmith.configs.ChatConfig;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.ap0stole.sheetsmith.services.excel.query.model.AggregateConfig;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnChatEnabled
public class AggregateTool implements QueryTool {

    private static final String OPERATIONS = "SUM|AVG|MIN|MAX|COUNT|COUNT_DISTINCT|MEDIAN";
    private static final String BLANK_KEY = "(blank)";

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ChatConfig chatConfig;

    @Override
    public String getType() {
        return "AGGREGATE";
    }

    @Override
    public String promptSpec() {
        return """
                AGGREGATE
                   Keys: "range" (data rows only, WITHOUT the header, e.g. "A2:D20"), "columnIndex" (0-based
                   absolute sheet column to aggregate), "operation" (%s)
                   Optional: "groupByColumnIndex" (0-based absolute — returns one value per distinct key,
                   sorted by value descending, max %d groups), "sheetName", "sheetIndex"
                   Non-numeric cells are skipped by SUM/AVG/MIN/MAX/MEDIAN; COUNT counts non-blank cells.
                   Example: {"tool": "AGGREGATE", "args": {"range": "A2:D20", "columnIndex": 2, "operation": "SUM", "groupByColumnIndex": 0}}"""
                .formatted(OPERATIONS, chatConfig.getMaxRows());
    }

    @Override
    public QueryResult execute(XSSFWorkbook workbook, Map<String, Object> properties) {
        AggregateConfig cfg = mapper.convertValue(properties, AggregateConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        CellRangeAddress range = CellRangeAddress.valueOf(cfg.getRange());
        String operation = normalizeOperation(cfg.getOperation());
        int column = requireColumn(cfg.getColumnIndex(), range, "columnIndex");

        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", operation);

        if (cfg.getGroupByColumnIndex() == null) {
            List<Object> values = new ArrayList<>();
            for (int r = range.getFirstRow(); r <= range.getLastRow(); r++) {
                values.add(QuerySupport.cellValue(sheet, r, column, evaluator));
            }
            Object value = compute(operation, values);
            data.put("value", value);

            log.info("AGGREGATE {} of column {} over {} = {}", operation, column, range.formatAsString(), value);
            String summary = "%s of column %s = %s".formatted(
                    label(operation), QuerySupport.colName(column), QuerySupport.fmt(value));
            return new QueryResult(QuerySupport.clip(summary, 120), data);
        }

        int groupCol = requireColumn(cfg.getGroupByColumnIndex(), range, "groupByColumnIndex");
        Map<String, List<Object>> buckets = new LinkedHashMap<>();
        for (int r = range.getFirstRow(); r <= range.getLastRow(); r++) {
            Object key = QuerySupport.cellValue(sheet, r, groupCol, evaluator);
            String keyText = QuerySupport.isBlank(key) ? BLANK_KEY : QuerySupport.asText(key);
            buckets.computeIfAbsent(keyText, k -> new ArrayList<>())
                    .add(QuerySupport.cellValue(sheet, r, column, evaluator));
        }

        List<Map<String, Object>> groups = new ArrayList<>();
        buckets.forEach((key, values) -> {
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("key", key);
            group.put("value", compute(operation, values));
            groups.add(group);
        });
        groups.sort(Comparator.comparing(
                (Map<String, Object> g) -> QuerySupport.asNumber(g.get("value")),
                Comparator.nullsLast(Comparator.reverseOrder())));

        int totalGroups = groups.size();
        boolean truncated = totalGroups > chatConfig.getMaxRows();
        List<Map<String, Object>> shown = truncated ? groups.subList(0, chatConfig.getMaxRows()) : groups;

        data.put("groups", new ArrayList<>(shown));
        data.put("truncated", truncated);

        log.info("AGGREGATE {} of column {} over {} grouped by column {} produced {} group(s)",
                operation, column, range.formatAsString(), groupCol, totalGroups);

        String summary = shown.isEmpty()
                ? "No groups found in " + range.formatAsString()
                : "%s — %s (highest of %d groups)".formatted(
                shown.getFirst().get("key"), QuerySupport.fmt(shown.getFirst().get("value")), totalGroups);
        return new QueryResult(QuerySupport.clip(summary, 120), data);
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String range = QuerySupport.propString(properties, "range");
        String operation = QuerySupport.propString(properties, "operation");
        Integer column = QuerySupport.propInt(properties, "columnIndex");
        Integer groupBy = QuerySupport.propInt(properties, "groupByColumnIndex");

        String text = verb(operation, tense) + " column " + QuerySupport.colName(column)
                + (range == null ? "" : " over " + range) + QuerySupport.sheetSuffix(properties);
        return groupBy == null ? text : text + ", grouped by column " + QuerySupport.colName(groupBy);
    }

    private String normalizeOperation(String operation) {
        String op = operation == null ? "" : operation.trim().toUpperCase();
        return switch (op) {
            case "SUM", "AVG", "MIN", "MAX", "COUNT", "COUNT_DISTINCT", "MEDIAN" -> op;
            case "AVERAGE" -> "AVG";
            default -> throw new IllegalArgumentException(
                    "Unknown operation \"" + operation + "\" — use one of " + OPERATIONS + ".");
        };
    }

    private int requireColumn(Integer columnIndex, CellRangeAddress range, String key) {
        if (columnIndex == null) {
            throw new IllegalArgumentException("\"" + key + "\" is required (0-based absolute sheet column).");
        }
        if (columnIndex < range.getFirstColumn() || columnIndex > range.getLastColumn()) {
            throw new IllegalArgumentException("Column " + columnIndex + " (" + key + ") is outside range "
                    + range.formatAsString() + ", which covers columns " + range.getFirstColumn()
                    + ".." + range.getLastColumn() + " — widen the range or pick a column inside it.");
        }
        return columnIndex;
    }

    private Object compute(String operation, List<Object> values) {
        List<Double> numbers = values.stream()
                .map(QuerySupport::asNumber)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        return switch (operation) {
            case "SUM" -> QuerySupport.round(numbers.stream().mapToDouble(Double::doubleValue).sum());
            case "AVG" -> numbers.isEmpty() ? null
                    : QuerySupport.round(numbers.stream().mapToDouble(Double::doubleValue).average().orElse(0));
            case "MIN" -> numbers.isEmpty() ? null : QuerySupport.round(numbers.getFirst());
            case "MAX" -> numbers.isEmpty() ? null : QuerySupport.round(numbers.getLast());
            case "MEDIAN" -> numbers.isEmpty() ? null : QuerySupport.round(median(numbers));
            case "COUNT" -> values.stream().filter(v -> !QuerySupport.isBlank(v)).count();
            case "COUNT_DISTINCT" -> values.stream().filter(v -> !QuerySupport.isBlank(v))
                    .map(QuerySupport::asText).distinct().count();
            default -> throw new IllegalArgumentException("Unsupported operation: " + operation);
        };
    }

    private double median(List<Double> sorted) {
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }

    private String label(String operation) {
        return switch (operation) {
            case "SUM" -> "Sum";
            case "AVG" -> "Average";
            case "MIN" -> "Minimum";
            case "MAX" -> "Maximum";
            case "COUNT" -> "Count";
            case "COUNT_DISTINCT" -> "Distinct count";
            case "MEDIAN" -> "Median";
            default -> operation;
        };
    }

    /** The operation supplies the verb here, so each one needs both readings. */
    private String verb(String operation, StepTense tense) {
        String op = operation == null ? "" : operation.trim().toUpperCase();
        return switch (op) {
            case "SUM" -> QuerySupport.verb(tense, "Sum", "Summed");
            case "AVG", "AVERAGE" -> QuerySupport.verb(tense, "Average", "Averaged");
            case "MIN" -> QuerySupport.verb(tense, "Take the minimum of", "Took the minimum of");
            case "MAX" -> QuerySupport.verb(tense, "Take the maximum of", "Took the maximum of");
            case "COUNT" -> QuerySupport.verb(tense, "Count values in", "Counted values in");
            case "COUNT_DISTINCT" -> QuerySupport.verb(tense, "Count distinct values in", "Counted distinct values in");
            case "MEDIAN" -> QuerySupport.verb(tense, "Take the median of", "Took the median of");
            default -> QuerySupport.verb(tense, "Aggregate", "Aggregated");
        };
    }
}
