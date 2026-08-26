package com.ap0stole.sheetsmith.services.excel.transform;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The escape hatch, for shapes no named rule covers. Deliberately the last option offered: a plan
 * card reading "replace /(\d{3})/ with $1" tells a reviewer far less than "as phone numbers", and
 * the review card is the only thing standing between a bad rule and 35 000 rewritten cells.
 */
@Component
public class RegexReplaceTransform implements ColumnTransform {

    @Override
    public String getType() {
        return "REGEX_REPLACE";
    }

    @Override
    public Optional<String> apply(String value, Map<String, Object> options) {
        if (value == null) return Optional.empty();
        String pattern = TransformOptions.require(options, "pattern", getType());
        String replacement = TransformOptions.unescape(TransformOptions.string(options, "replacement", ""));
        try {
            return Optional.of(Pattern.compile(pattern).matcher(value).replaceAll(replacement));
        } catch (PatternSyntaxException e) {
            // Thrown, not skipped: a broken pattern is broken for every row, and the model needs
            // to hear about it once rather than get "35 000 values left alone".
            throw new IllegalArgumentException("\"pattern\" is not a valid regular expression: " + e.getDescription());
        }
    }

    @Override
    public String promptSpec() {
        return """
                REGEX_REPLACE — keys: "pattern" (required, Java regex), "replacement" (defaults to empty;
                     $1, $2 refer to capture groups). LAST RESORT — prefer a named rule above when one
                     fits, because those read clearly in the plan the user has to approve.""";
    }

    @Override
    public String describe(Map<String, Object> options) {
        String pattern = TransformOptions.string(options, "pattern", null);
        return pattern == null
                ? "with a pattern replacement"
                : "with the pattern /" + pattern + "/ replaced";
    }
}
