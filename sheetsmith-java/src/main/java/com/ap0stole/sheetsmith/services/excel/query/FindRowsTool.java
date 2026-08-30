package com.ap0stole.sheetsmith.services.excel.query;

import com.ap0stole.sheetsmith.configs.ConditionalOnChatEnabled;
import com.ap0stole.sheetsmith.configs.ChatConfig;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.ap0stole.sheetsmith.services.excel.query.model.FilterCriterion;
import com.ap0stole.sheetsmith.services.excel.query.model.FindRowsConfig;
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

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnChatEnabled
public class FindRowsTool implements QueryTool {

    private static final int DEFAULT_LIMIT = 10;

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ChatConfig chatConfig;

    @Override
    public String getType() {
        return "FIND_ROWS";
    }

    @Override
    public String promptSpec() {
        return """
                FIND_ROWS
                   Keys: "range" (data rows only, e.g. "A2:D20")
                   Optional: "filters" (array of {"columnIndex": 0-based absolute, "operator": "="|"!="|">"|">="
                   |"<"|"<="|"contains", "value": "..."} — all must match), "sortColumnIndex" (0-based absolute),
                   "ascending" (boolean, default true), "limit" (default %d, max %d), "columns" (array of 0-based
                   absolute columns to return, default every column of the range), "sheetName", "sheetIndex"
                   Use it to answer "which rows...", "top N by...", "how many rows where...".
                   Example: {"tool": "FIND_ROWS", "args": {"range": "A2:D20", "filters": [{"columnIndex": 1, "operator": ">", "value": "100"}], "sortColumnIndex": 2, "ascending": false, "limit": 5}}"""
                .formatted(DEFAULT_LIMIT, chatConfig.getMaxRows());
    }

    @Override
    public QueryResult execute(XSSFWorkbook workbook, Map<String, Object> properties) {
        FindRowsConfig cfg = mapper.convertValue(properties, FindRowsConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        CellRangeAddress range = CellRangeAddress.valueOf(cfg.getRange());
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

        List<FilterCriterion> filters = cfg.getFilters() == null ? List.of() : cfg.getFilters();
        for (FilterCriterion filter : filters) {
            validate(filter);
        }

        List<Integer> matched = new ArrayList<>();
        for (int r = range.getFirstRow(); r <= range.getLastRow(); r++) {
            if (matches(sheet, r, filters, evaluator)) {
                matched.add(r);
            }
        }

        if (cfg.getSortColumnIndex() != null) {
            int sortCol = cfg.getSortColumnIndex();
            Comparator<Object> byValue = FindRowsTool::compareValues;
            Comparator<Object> directed = cfg.isAscending() ? byValue : byValue.reversed();
            matched.sort(Comparator.comparing(
                    (Integer r) -> QuerySupport.cellValue(sheet, r, sortCol, evaluator),
                    Comparator.nullsLast(directed)));
        }

        int limit = resolveLimit(cfg.getLimit());
        List<Integer> columns = resolveColumns(cfg.getColumns(), range);
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < matched.size() && i < limit; i++) {
            List<Object> row = new ArrayList<>();
            for (Integer c : columns) {
                row.add(QuerySupport.cellValue(sheet, matched.get(i), c, evaluator));
            }
            rows.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rows", rows);
        data.put("returned", rows.size());
        data.put("matched", matched.size());
        data.put("truncated", matched.size() > rows.size());

        log.info("FIND_ROWS over {} matched {} row(s), returned {}",
                range.formatAsString(), matched.size(), rows.size());

        String summary = summarise(matched.size(), rows.size());
        return new QueryResult(QuerySupport.clip(summary, 120), data);
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String range = QuerySupport.propString(properties, "range");
        String where = (range == null ? "" : " in " + range) + QuerySupport.sheetSuffix(properties);
        Integer sortCol = QuerySupport.propInt(properties, "sortColumnIndex");
        int limit = resolveLimit(QuerySupport.propInt(properties, "limit"));

        Object ascending = properties == null ? null : properties.get("ascending");
        boolean asc = !"false".equalsIgnoreCase(String.valueOf(ascending));

        String verb = QuerySupport.verb(tense, "Find", "Found");
        String text = sortCol == null
                ? "%s up to %d rows%s".formatted(verb, limit, where)
                : "%s the %d %s rows%s by column %s".formatted(
                verb, limit, asc ? "lowest" : "highest", where, QuerySupport.colName(sortCol));

        int filters = filterCount(properties);
        if (filters == 0) {
            return text;
        }
        return text + " matching %d condition%s".formatted(filters, plural(filters));
    }

    private int resolveLimit(Integer requested) {
        int limit = requested == null || requested <= 0 ? DEFAULT_LIMIT : requested;
        return Math.min(limit, chatConfig.getMaxRows());
    }

    private List<Integer> resolveColumns(List<Integer> requested, CellRangeAddress range) {
        if (requested != null && !requested.isEmpty()) {
            return requested;
        }
        List<Integer> columns = new ArrayList<>();
        for (int c = range.getFirstColumn(); c <= range.getLastColumn(); c++) {
            columns.add(c);
        }
        return columns;
    }

    private void validate(FilterCriterion filter) {
        if (filter.getColumnIndex() == null) {
            throw new IllegalArgumentException("Every filter needs a \"columnIndex\" (0-based absolute sheet column).");
        }
        String op = filter.getOperator() == null ? "" : filter.getOperator().trim().toLowerCase();
        if (!List.of("=", "==", "!=", "<>", ">", ">=", "<", "<=", "contains").contains(op)) {
            throw new IllegalArgumentException("Unknown filter operator \"" + filter.getOperator()
                    + "\" — use =, !=, >, >=, <, <= or contains.");
        }
    }

    private boolean matches(XSSFSheet sheet, int rowIdx, List<FilterCriterion> filters, FormulaEvaluator evaluator) {
        for (FilterCriterion filter : filters) {
            Object actual = QuerySupport.cellValue(sheet, rowIdx, filter.getColumnIndex(), evaluator);
            if (!matches(actual, filter.getOperator(), filter.getValue())) {
                return false;
            }
        }
        return true;
    }

    private boolean matches(Object actual, String operator, String expected) {
        String op = operator.trim().toLowerCase();
        String actualText = QuerySupport.asText(actual);

        if ("contains".equals(op)) {
            return actualText != null && expected != null
                    && actualText.toLowerCase().contains(expected.toLowerCase());
        }

        Double a = QuerySupport.asNumber(actual);
        Double b = QuerySupport.asNumber(expected);
        int cmp = a != null && b != null
                ? Double.compare(a, b)
                : nullSafe(actualText).compareToIgnoreCase(nullSafe(expected));

        return switch (op) {
            case "=", "==" -> cmp == 0;
            case "!=", "<>" -> cmp != 0;
            case ">" -> cmp > 0;
            case ">=" -> cmp >= 0;
            case "<" -> cmp < 0;
            case "<=" -> cmp <= 0;
            default -> false;
        };
    }

    /** Numeric when both sides are numbers, case-insensitive text otherwise — the model sends everything as strings. */
    private static int compareValues(Object a, Object b) {
        Double na = QuerySupport.asNumber(a);
        Double nb = QuerySupport.asNumber(b);
        if (na != null && nb != null) return Double.compare(na, nb);
        return nullSafe(QuerySupport.asText(a)).compareToIgnoreCase(nullSafe(QuerySupport.asText(b)));
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /** How many rows matched, and whether the answer had to stop short of them. */
    private static String summarise(int matched, int returned) {
        if (matched > returned) {
            return "%d rows matched, returned the first %d".formatted(matched, returned);
        }
        return "%d row%s matched".formatted(matched, plural(matched));
    }

    /** How many conditions the step carries, or none where it carries no list at all. */
    private static int filterCount(Map<String, Object> properties) {
        if (properties != null && properties.get("filters") instanceof List<?> list) {
            return list.size();
        }
        return 0;
    }

    /** The "s" that makes a count read as English. */
    private static String plural(int count) {
        return count == 1 ? "" : "s";
    }
}
