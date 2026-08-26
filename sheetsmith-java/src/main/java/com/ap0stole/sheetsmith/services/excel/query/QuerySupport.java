package com.ap0stole.sheetsmith.services.excel.query;

import com.ap0stole.sheetsmith.services.excel.CellValues;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellReference;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Cell reading and value coercion shared by the query tools. Sheets are sparse and their values
 * come from an LLM-chosen range, so every read here tolerates missing rows, missing cells and
 * broken formulas rather than throwing.
 */
final class QuerySupport {

    private QuerySupport() {
    }

    /** The leading verb, the only part of a step's sentence that differs between a proposal and a record. */
    static String verb(StepTense tense, String imperative, String past) {
        return tense == StepTense.IMPERATIVE ? imperative : past;
    }

    static Object cellValue(Sheet sheet, int rowIdx, int colIdx, FormulaEvaluator evaluator) {
        Row row = sheet.getRow(rowIdx);
        return row == null ? null : cellValue(row.getCell(colIdx), evaluator);
    }

    /** Raw typed value; formula cells yield their result, never the formula text. */
    static Object cellValue(Cell cell, FormulaEvaluator evaluator) {
        return CellValues.of(cell, evaluator);
    }

    static String errorText(int code) {
        return CellValues.errorText(code);
    }

    /** Numeric view of a cell value, or null when it is not a number. Booleans are not numbers here. */
    static Double asNumber(Object value) {
        return CellValues.asNumber(value);
    }

    static String asText(Object value) {
        return CellValues.asText(value);
    }

    static boolean isBlank(Object value) {
        String s = asText(value);
        return s == null || s.isBlank();
    }

    /** Integral doubles become longs so the JSON handed to the model reads "10" and not "10.0". */
    static Object tidy(double value) {
        return CellValues.tidy(value);
    }

    /** Same as {@link #tidy}, but first drops the float noise that summing a column accumulates. */
    static Object round(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return null;
        return tidy(BigDecimal.valueOf(value).setScale(10, RoundingMode.HALF_UP).doubleValue());
    }

    static String fmt(Object value) {
        if (value == null) return "n/a";
        Double d = asNumber(value);
        if (d == null) return String.valueOf(value);
        Object tidied = tidy(d);
        return tidied == null ? String.valueOf(value) : String.valueOf(tidied);
    }

    static String colName(Integer columnIndex) {
        return columnIndex == null ? "?" : CellReference.convertNumToColString(columnIndex);
    }

    static String propString(Map<String, Object> properties, String key) {
        Object v = properties == null ? null : properties.get(key);
        if (v == null) return null;
        String s = String.valueOf(v);
        return s.isBlank() ? null : s;
    }

    static Integer propInt(Map<String, Object> properties, String key) {
        Object v = properties == null ? null : properties.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Integer.valueOf(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * {@code  on "Sales"} / {@code  on sheet 2} / empty — matches the wording the action handlers
     * use, since both land in the same step chain.
     */
    static String sheetSuffix(Map<String, Object> properties) {
        String name = propString(properties, "sheetName");
        if (name != null) return " on \"" + name + "\"";
        Integer index = propInt(properties, "sheetIndex");
        return index == null ? "" : " on sheet " + (index + 1);
    }

    static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
