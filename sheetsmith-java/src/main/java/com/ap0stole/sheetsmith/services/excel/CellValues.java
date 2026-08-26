package com.ap0stole.sheetsmith.services.excel;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.FormulaEvaluator;

/**
 * Reading a cell as the value a human would say it holds. Shared by the query tools and the column
 * transforms, because both have to agree on what a cell "is" — a phone stored as a number must read
 * back as 16828971263 and never as 1.6828971263E10, whichever side is looking at it.
 */
public final class CellValues {

    private CellValues() {
    }

    /** Raw typed value; formula cells yield their result, never the formula text. */
    public static Object of(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case NUMERIC -> isDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toString()
                    : tidy(cell.getNumericCellValue());
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> cell.getBooleanCellValue();
            case FORMULA -> formulaValue(cell, evaluator);
            default -> null;
        };
    }

    /** Integral doubles become longs so "10" is not shown or matched as "10.0". */
    public static Object tidy(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return null;
        if (value == Math.rint(value) && Math.abs(value) < 1e15) return (long) value;
        return value;
    }

    public static String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** Numeric view of a value, or null when it is not a number. Booleans are not numbers here. */
    public static Double asNumber(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Double.valueOf(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public static String errorText(int code) {
        try {
            return FormulaError.forInt((byte) code).getString();
        } catch (RuntimeException e) {
            return "#ERROR";
        }
    }

    private static Object formulaValue(Cell cell, FormulaEvaluator evaluator) {
        CellValue cv = null;
        if (evaluator != null) {
            try {
                cv = evaluator.evaluate(cell);
            } catch (RuntimeException e) {
                cv = null;
            }
        }
        if (cv == null) return cachedFormulaValue(cell);
        return switch (cv.getCellType()) {
            case NUMERIC -> isDateFormatted(cell)
                    ? DateUtil.getLocalDateTime(cv.getNumberValue()).toString()
                    : tidy(cv.getNumberValue());
            case STRING -> cv.getStringValue();
            case BOOLEAN -> cv.getBooleanValue();
            case ERROR -> errorText(cv.getErrorValue());
            default -> null;
        };
    }

    private static Object cachedFormulaValue(Cell cell) {
        try {
            return switch (cell.getCachedFormulaResultType()) {
                case NUMERIC -> isDateFormatted(cell)
                        ? cell.getLocalDateTimeCellValue().toString()
                        : tidy(cell.getNumericCellValue());
                case STRING -> cell.getStringCellValue();
                case BOOLEAN -> cell.getBooleanCellValue();
                default -> null;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isDateFormatted(Cell cell) {
        try {
            return DateUtil.isCellDateFormatted(cell);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
