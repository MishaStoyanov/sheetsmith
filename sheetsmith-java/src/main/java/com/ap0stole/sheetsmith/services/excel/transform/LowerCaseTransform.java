package com.ap0stole.sheetsmith.services.excel.transform;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class LowerCaseTransform implements ColumnTransform {

    @Override
    public String getType() {
        return "LOWER";
    }

    @Override
    public Optional<String> apply(String value, Map<String, Object> options) {
        return value == null ? Optional.empty() : Optional.of(value.toLowerCase(Locale.ROOT));
    }

    @Override
    public String promptSpec() {
        return "LOWER — no extra keys. Lower-cases the whole value. Useful for email columns.";
    }

    @Override
    public String describe(Map<String, Object> options) {
        return "in lower case";
    }
}
