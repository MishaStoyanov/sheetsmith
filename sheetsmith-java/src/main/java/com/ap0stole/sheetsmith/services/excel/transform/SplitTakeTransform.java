package com.ap0stole.sheetsmith.services.excel.transform;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * One piece of a value that packs several fields together — the street line of an address whose
 * city sits after a line break, the surname before a comma.
 */
@Component
public class SplitTakeTransform implements ColumnTransform {

    @Override
    public String getType() {
        return "SPLIT_TAKE";
    }

    @Override
    public Optional<String> apply(String value, Map<String, Object> options) {
        if (value == null) return Optional.empty();

        String separator = TransformOptions.unescape(TransformOptions.require(options, "separator", getType()));
        int index = TransformOptions.integer(options, "index", 0);

        String[] parts = value.split(java.util.regex.Pattern.quote(separator), -1);
        int resolved = index < 0 ? parts.length + index : index;
        if (resolved < 0 || resolved >= parts.length) {
            // The value simply does not have that many pieces — leaving it whole beats blanking it.
            return Optional.empty();
        }
        return Optional.of(parts[resolved].strip());
    }

    @Override
    public String promptSpec() {
        return """
                SPLIT_TAKE — keys: "separator" (required, literal; write "\\n" for a line break inside a
                     cell), "index" (0-based, default 0; -1 means the last piece). Splits the value and
                     keeps one piece. Values with too few pieces are left alone.""";
    }

    @Override
    public String describe(Map<String, Object> options) {
        int index = TransformOptions.integer(options, "index", 0);
        String separator = TransformOptions.string(options, "separator", null);
        String which = switch (index) {
            case 0 -> "first";
            case -1 -> "last";
            case 1 -> "second";
            case 2 -> "third";
            default -> index < 0 ? (-index) + "th from the end" : (index + 1) + "th";
        };
        String on = separator == null ? "" : " split on \"" + separator + "\"";
        return "keeping the " + which + " piece" + on;
    }
}
