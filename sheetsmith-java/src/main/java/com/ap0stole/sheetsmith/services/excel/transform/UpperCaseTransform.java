package com.ap0stole.sheetsmith.services.excel.transform;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class UpperCaseTransform implements ColumnTransform {

    @Override
    public String getType() {
        return "UPPER";
    }

    @Override
    public Optional<String> apply(String value, Map<String, Object> options) {
        return value == null ? Optional.empty() : Optional.of(value.toUpperCase(Locale.ROOT));
    }

    @Override
    public String promptSpec() {
        return "UPPER — no extra keys. Upper-cases the whole value.";
    }

    @Override
    public String describe(Map<String, Object> options) {
        return "in upper case";
    }
}
