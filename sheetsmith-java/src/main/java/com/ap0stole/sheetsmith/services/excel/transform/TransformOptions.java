package com.ap0stole.sheetsmith.services.excel.transform;

import java.util.Map;

/**
 * Reading a transform's extra arguments out of the flat property map the LLM sends. Everything here
 * tolerates a missing or mistyped key, because the map comes from a model and not from a caller.
 */
public final class TransformOptions {

    private TransformOptions() {
    }

    public static String string(Map<String, Object> options, String key, String fallback) {
        Object value = options == null ? null : options.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    public static int integer(Map<String, Object> options, String key, int fallback) {
        Object value = options == null ? null : options.get(key);
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    /**
     * Turns the escapes a model writes in JSON string arguments into the characters they mean.
     * A separator arrives as the two characters {@code \} and {@code n} often enough that taking
     * it literally would silently match nothing.
     */
    public static String unescape(String text) {
        if (text == null || text.indexOf('\\') < 0) return text;
        return text.replace("\\r\\n", "\n")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }

    /** Required argument, with a message the model can act on when it forgot one. */
    public static String require(Map<String, Object> options, String key, String transform) {
        String value = string(options, key, null);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(
                    "\"" + transform + "\" needs a \"" + key + "\" argument.");
        }
        return value;
    }
}
