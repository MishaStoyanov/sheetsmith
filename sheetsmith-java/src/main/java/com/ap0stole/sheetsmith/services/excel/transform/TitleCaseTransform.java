package com.ap0stole.sheetsmith.services.excel.transform;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Each word capitalised. Apostrophes and hyphens start a word too, so shouted name columns come
 * back as O'Reilly and Jean-Luc rather than O'reilly and Jean-luc.
 */
@Component
public class TitleCaseTransform implements ColumnTransform {

    @Override
    public String getType() {
        return "TITLE_CASE";
    }

    @Override
    public Optional<String> apply(String value, Map<String, Object> options) {
        if (value == null) return Optional.empty();

        StringBuilder out = new StringBuilder(value.length());
        boolean startOfWord = true;
        for (char c : value.toCharArray()) {
            out.append(startOfWord ? Character.toUpperCase(c) : Character.toLowerCase(c));
            startOfWord = !Character.isLetterOrDigit(c);
        }
        return Optional.of(out.toString());
    }

    @Override
    public String promptSpec() {
        return """
                TITLE_CASE — no extra keys. Capitalises the first letter of every word and lower-cases
                     the rest, treating apostrophes and hyphens as word starts (O'Reilly, Jean-Luc).""";
    }

    @Override
    public String describe(Map<String, Object> options) {
        return "in title case";
    }
}
