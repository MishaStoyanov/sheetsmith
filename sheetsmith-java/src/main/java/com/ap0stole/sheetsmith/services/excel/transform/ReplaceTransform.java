package com.ap0stole.sheetsmith.services.excel.transform;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/** Literal find-and-replace — no pattern syntax, so "." means a dot and nothing else. */
@Component
public class ReplaceTransform implements ColumnTransform {

    @Override
    public String getType() {
        return "REPLACE";
    }

    @Override
    public Optional<String> apply(String value, Map<String, Object> options) {
        if (value == null) return Optional.empty();
        String find = TransformOptions.unescape(TransformOptions.require(options, "find", getType()));
        String replacement = TransformOptions.unescape(TransformOptions.string(options, "replace", ""));
        return Optional.of(value.replace(find, replacement));
    }

    @Override
    public String promptSpec() {
        return """
                REPLACE — keys: "find" (required, literal text), "replace" (defaults to empty, i.e. delete).
                     Plain text, not a pattern: every occurrence of "find" is swapped for "replace".""";
    }

    @Override
    public String describe(Map<String, Object> options) {
        String find = TransformOptions.string(options, "find", null);
        String replacement = TransformOptions.string(options, "replace", "");
        if (find == null) return "with a literal replacement";
        return replacement.isEmpty()
                ? "with \"" + find + "\" removed"
                : "with \"" + find + "\" replaced by \"" + replacement + "\"";
    }
}
