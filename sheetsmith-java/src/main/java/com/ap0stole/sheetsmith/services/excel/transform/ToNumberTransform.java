package com.ap0stole.sheetsmith.services.excel.transform;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Numbers that were saved as text — the classic reason a column refuses to sum. Currency symbols,
 * thousands separators and stray spaces come off, and the result is written as a real number.
 */
@Component
public class ToNumberTransform implements ColumnTransform {

    private static final Pattern KEEP = Pattern.compile("[^0-9,.\\-+]");

    @Override
    public String getType() {
        return "TO_NUMBER";
    }

    @Override
    public boolean numeric() {
        return true;
    }

    @Override
    public Optional<String> apply(String value, Map<String, Object> options) {
        if (value == null) return Optional.empty();

        boolean negative = value.contains("(") && value.contains(")");
        String cleaned = KEEP.matcher(value).replaceAll("");
        if (cleaned.isBlank()) return Optional.empty();

        cleaned = separators(cleaned);
        try {
            double parsed = Double.parseDouble(cleaned);
            // Accounting columns write a loss as (1 234), which parses positive without this.
            return Optional.of(String.valueOf(negative ? -Math.abs(parsed) : parsed));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Decides which of "." and "," is the decimal point. When both appear the rightmost one is it,
     * which reads 1,234.56 and 1.234,56 correctly. A lone comma is ambiguous, so it goes by the
     * group it separates: three trailing digits is a thousands separator, anything else a decimal.
     */
    private String separators(String text) {
        int lastDot = text.lastIndexOf('.');
        int lastComma = text.lastIndexOf(',');

        if (lastDot >= 0 && lastComma >= 0) {
            return lastDot > lastComma
                    ? text.replace(",", "")
                    : text.replace(".", "").replace(',', '.');
        }
        if (lastComma >= 0) {
            boolean thousands = text.length() - lastComma - 1 == 3;
            return thousands ? text.replace(",", "") : text.replace(',', '.');
        }
        return text;
    }

    @Override
    public String promptSpec() {
        return """
                TO_NUMBER — no extra keys. Turns text that holds a number ("$1,234.56", "1 234,56",
                     "(500)" for negative) into a real number the sheet can sum and sort. Values that
                     hold no number are left alone.""";
    }

    @Override
    public String describe(Map<String, Object> options) {
        return "as real numbers instead of text";
    }
}
