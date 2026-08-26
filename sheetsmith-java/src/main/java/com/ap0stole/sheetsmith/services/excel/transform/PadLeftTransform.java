package com.ap0stole.sheetsmith.services.excel.transform;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/** Restores the leading zeros Excel eats from zip codes and IDs the moment it decides they are numbers. */
@Component
public class PadLeftTransform implements ColumnTransform {

    @Override
    public String getType() {
        return "PAD_LEFT";
    }

    @Override
    public Optional<String> apply(String value, Map<String, Object> options) {
        if (value == null) return Optional.empty();

        int length = TransformOptions.integer(options, "length", 0);
        if (length <= 0) {
            throw new IllegalArgumentException("\"PAD_LEFT\" needs a positive \"length\".");
        }
        String fill = TransformOptions.string(options, "fill", "0");
        if (fill.isEmpty()) fill = "0";

        if (value.length() >= length) return Optional.of(value);

        StringBuilder out = new StringBuilder();
        char pad = fill.charAt(0);
        out.append(String.valueOf(pad).repeat(length - value.length())).append(value);
        return Optional.of(out.toString());
    }

    @Override
    public String promptSpec() {
        return """
                PAD_LEFT — keys: "length" (required, target width), "fill" (single character, default "0").
                     Pads short values on the left; values already that long are left as they are.
                     Use it to put back the leading zeros Excel dropped from zip codes and IDs.""";
    }

    @Override
    public String describe(Map<String, Object> options) {
        int length = TransformOptions.integer(options, "length", 0);
        String fill = TransformOptions.string(options, "fill", "0");
        return length <= 0
                ? "padded on the left"
                : "padded to " + length + " characters with \"" + fill + "\"";
    }
}
