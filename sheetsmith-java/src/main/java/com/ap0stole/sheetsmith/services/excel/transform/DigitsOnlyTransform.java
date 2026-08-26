package com.ap0stole.sheetsmith.services.excel.transform;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Everything that is not a digit thrown away — IDs, codes, phone numbers stripped to bare digits. */
@Component
public class DigitsOnlyTransform implements ColumnTransform {

    private static final Pattern NON_DIGIT = Pattern.compile("\\D");

    @Override
    public String getType() {
        return "DIGITS_ONLY";
    }

    @Override
    public Optional<String> apply(String value, Map<String, Object> options) {
        if (value == null) return Optional.empty();
        String digits = NON_DIGIT.matcher(value).replaceAll("");
        // Dropping every character would leave an empty cell where data used to be.
        return digits.isEmpty() ? Optional.empty() : Optional.of(digits);
    }

    @Override
    public String promptSpec() {
        return """
                DIGITS_ONLY — no extra keys. Keeps just the digits, dropping spaces, punctuation and
                     letters. Values holding no digit at all are left alone.""";
    }

    @Override
    public String describe(Map<String, Object> options) {
        return "as digits only";
    }
}
