package com.ap0stole.sheetsmith.services.excel.transform;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Leading, trailing and doubled whitespace removed — line breaks inside a cell included. */
@Component
public class TrimTransform implements ColumnTransform {

    private static final Pattern RUNS = Pattern.compile("\\s+");

    @Override
    public String getType() {
        return "TRIM";
    }

    @Override
    public Optional<String> apply(String value, Map<String, Object> options) {
        if (value == null) return Optional.empty();
        return Optional.of(RUNS.matcher(value.strip()).replaceAll(" "));
    }

    @Override
    public String promptSpec() {
        return """
                TRIM — no extra keys. Strips leading and trailing whitespace and collapses every run
                     of spaces, tabs and line breaks inside the value into a single space.""";
    }

    @Override
    public String describe(Map<String, Object> options) {
        return "with surrounding and doubled whitespace removed";
    }
}
