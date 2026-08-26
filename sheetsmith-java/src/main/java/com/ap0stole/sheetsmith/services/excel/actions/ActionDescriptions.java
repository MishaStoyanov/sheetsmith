package com.ap0stole.sheetsmith.services.excel.actions;

import com.ap0stole.sheetsmith.services.excel.StepTense;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Wording helpers shared by every {@code ActionHandler#describe}. The property maps come straight
 * from the LLM, so every method tolerates missing keys, nulls and wrong types and returns
 * {@code null} rather than throwing.
 */
final class ActionDescriptions {

    private ActionDescriptions() {
    }

    /** The leading verb, the only part of a step's sentence that differs between a proposal and a record. */
    static String verb(StepTense tense, String imperative, String past) {
        return tense == StepTense.IMPERATIVE ? imperative : past;
    }

    /** Scalar value of {@code key} as trimmed text, or {@code null} when absent/blank/non-scalar. */
    static String text(Map<String, Object> properties, String key) {
        Object raw = raw(properties, key);
        if (raw == null || raw instanceof Map<?, ?> || raw instanceof Collection<?>) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() || "null".equalsIgnoreCase(value) ? null : value;
    }

    static Integer integer(Map<String, Object> properties, String key) {
        Object raw = raw(properties, key);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        String value = text(properties, key);
        if (value == null) {
            return null;
        }
        try {
            return (int) Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static boolean flag(Map<String, Object> properties, String key, boolean fallback) {
        Object raw = raw(properties, key);
        if (raw instanceof Boolean bool) {
            return bool;
        }
        if (raw instanceof Number number) {
            return number.doubleValue() != 0;
        }
        String value = text(properties, key);
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        return fallback;
    }

    /** Range or cell reference without sheet prefix and absolute markers; {@code B12:B12} collapses to {@code B12}. */
    static String range(Map<String, Object> properties, String key) {
        String value = text(properties, key);
        if (value == null) {
            return null;
        }
        String cleaned = value.substring(value.lastIndexOf('!') + 1).replace("$", "").trim();
        int colon = cleaned.indexOf(':');
        if (colon > 0 && cleaned.substring(0, colon).equalsIgnoreCase(cleaned.substring(colon + 1))) {
            cleaned = cleaned.substring(0, colon);
        }
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** Formula normalised to a single leading {@code =}. */
    static String formula(Map<String, Object> properties, String key) {
        String value = text(properties, key);
        return value == null ? null : "=" + value.replaceAll("^=+", "");
    }

    /** {@code "column C"} for a 0-based {@code columnIndex}. */
    static String column(Map<String, Object> properties, String key) {
        Integer index = integer(properties, key);
        String letter = index == null ? null : columnLetter(index);
        return letter == null ? null : "column " + letter;
    }

    /** 0-based column index as an Excel column letter (0 -> A, 26 -> AA). */
    static String columnLetter(int index) {
        if (index < 0) {
            return null;
        }
        StringBuilder letters = new StringBuilder();
        for (int i = index; i >= 0; i = i / 26 - 1) {
            letters.insert(0, (char) ('A' + i % 26));
        }
        return letters.toString();
    }

    static String quoted(String value) {
        return '"' + value + '"';
    }

    /**
     * The rows an action was pointed at, said the way a person would — {@code rows 5:20},
     * {@code 16 rows from row 5} — from either a range or {@code at} plus {@code count}. Shared so
     * that the same keys read the same on every card that names rows.
     */
    static String rowSpan(Map<String, Object> properties) {
        String range = text(properties, "range");
        if (range != null) {
            return "rows " + range;
        }
        Integer at = integer(properties, "at");
        if (at == null) {
            return "the rows";
        }
        Integer count = integer(properties, "count");
        int rows = count == null || count < 1 ? 1 : count;
        return rows == 1 ? "row " + at : rows + " rows from row " + at;
    }

    /** {@code  on "Sales"} when the action names a sheet, empty string otherwise. */
    static String sheetSuffix(Map<String, Object> properties) {
        String sheet = text(properties, "sheetName");
        if (sheet == null) {
            sheet = text(properties, "sourceSheet");
        }
        return sheet == null ? "" : " on " + quoted(sheet);
    }

    /** Comparison operator in words, or {@code null} when it is missing or unrecognised. */
    static String comparison(String operator) {
        if (operator == null) {
            return null;
        }
        return switch (operator.trim().toUpperCase(Locale.ROOT)) {
            case ">", "GT" -> "greater than";
            case ">=", "=>", "GE" -> "at least";
            case "<", "LT" -> "less than";
            case "<=", "=<", "LE" -> "at most";
            case "=", "==", "EQ" -> "equal to";
            case "!=", "<>", "NE" -> "not equal to";
            default -> null;
        };
    }

    /** Styling bits chained with commas, e.g. {@code blue background (#1E3A8A), bold}; empty when nothing is styled. */
    static String styling(Map<String, Object> properties) {
        List<String> bits = new ArrayList<>();
        String background = color(properties, "backgroundColor");
        if (background != null) {
            bits.add(colorBit(background, "background"));
        }
        String font = color(properties, "fontColor");
        if (font != null) {
            bits.add(colorBit(font, "text"));
        }
        if (flag(properties, "bold", false)) {
            bits.add("bold");
        }
        Integer size = integer(properties, "fontSize");
        if (size != null && size > 0) {
            bits.add(size + "pt font");
        }
        return String.join(", ", bits);
    }

    /**
     * A colour on its own — {@code light green (#BBF7D0)} — for the actions whose sentence names
     * colours rather than applying them to something, falling back to the value the handler will
     * use when none was asked for.
     */
    static String colorWords(String value, String fallback) {
        String chosen = value == null || value.isBlank() ? fallback : value.trim();
        String hex = hex(chosen);
        return hex == null ? chosen.toLowerCase(Locale.ROOT) : colorName(hex) + " (" + hex + ")";
    }

    private static Object raw(Map<String, Object> properties, String key) {
        return properties == null ? null : properties.get(key);
    }

    /** Colour value, also accepting the nested {@code {"color": "#..."}} form the LLM sometimes emits. */
    private static String color(Map<String, Object> properties, String key) {
        Object raw = raw(properties, key);
        if (raw instanceof Map<?, ?> nested) {
            Object inner = nested.get("color");
            if (inner == null) {
                return null;
            }
            String value = String.valueOf(inner).trim();
            return value.isEmpty() || "null".equalsIgnoreCase(value) ? null : value;
        }
        return text(properties, key);
    }

    private static String colorBit(String value, String what) {
        String hex = hex(value);
        return hex == null
                ? value.toLowerCase(Locale.ROOT) + " " + what
                : colorName(hex) + " " + what + " (" + hex + ")";
    }

    private static String hex(String value) {
        String digits = value.startsWith("#") ? value.substring(1) : value;
        return digits.matches("(?i)[0-9a-f]{6}") ? "#" + digits.toUpperCase(Locale.ROOT) : null;
    }

    /**
     * The palette the model is instructed to use, so the colour the user asked for is the colour
     * they read back. Anything else falls through to the hue estimate below.
     */
    private static final Map<String, String> PALETTE = Map.ofEntries(
            Map.entry("#1E3A8A", "blue"), Map.entry("#BFDBFE", "light blue"), Map.entry("#0EA5E9", "sky blue"),
            Map.entry("#DC2626", "red"), Map.entry("#FECACA", "light red"),
            Map.entry("#15803D", "green"), Map.entry("#BBF7D0", "light green"),
            Map.entry("#CA8A04", "yellow"), Map.entry("#FEF08A", "light yellow"),
            Map.entry("#EA580C", "orange"), Map.entry("#7C3AED", "purple"),
            Map.entry("#6B7280", "grey"), Map.entry("#1F2937", "dark"),
            Map.entry("#FFFFFF", "white"), Map.entry("#000000", "black"));

    /** Rough colour name derived from hue; the exact hex is always shown next to it, so approximation is safe. */
    private static String colorName(String hex) {
        String known = PALETTE.get(hex);
        if (known != null) return known;

        int rgb = Integer.parseInt(hex.substring(1), 16);
        double r = ((rgb >> 16) & 0xFF) / 255.0;
        double g = ((rgb >> 8) & 0xFF) / 255.0;
        double b = (rgb & 0xFF) / 255.0;
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double delta = max - min;
        if (delta < 0.09) {
            double lightness = (max + min) / 2;
            if (lightness > 0.9) {
                return "white";
            }
            return lightness < 0.1 ? "black" : "grey";
        }
        double hue;
        if (max == r) {
            hue = 60 * (((g - b) / delta + 6) % 6);
        } else if (max == g) {
            hue = 60 * ((b - r) / delta + 2);
        } else {
            hue = 60 * ((r - g) / delta + 4);
        }
        if (hue < 15 || hue >= 345) return "red";
        if (hue < 45) return "orange";
        if (hue < 70) return "yellow";
        if (hue < 160) return "green";
        if (hue < 200) return "teal";
        if (hue < 260) return "blue";
        if (hue < 290) return "purple";
        return "pink";
    }
}
