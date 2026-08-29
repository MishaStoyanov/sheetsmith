package com.ap0stole.sheetsmith.services.excel.actions.format;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.format.BordersConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.CellStyles;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Draws the lines that turn a block of values into a table someone can read.
 * <p>
 * The one idea worth knowing: {@code sides} names sides of the <em>range</em>, not of every cell in
 * it. "bottom" under a five-row block is a line under the block, which is what "underline the
 * header" means and what drawing it under all five rows would not be; "all" is the exception that
 * does mean every cell, because that is the only thing it can mean. Sides combine — {@code
 * "outline,bottom"} boxes a header and rules it off in one step — which is why the key is a list.
 * <p>
 * Each cell therefore gets its own combination of edges, and the shared style cache is keyed on
 * that combination, so a 200-cell block still creates the handful of styles it actually needs.
 */
@Slf4j
@Component
public class SetBordersHandler implements ActionHandler {

    private enum Edge {TOP, BOTTOM, LEFT, RIGHT}

    private static final String DEFAULT_COLOR = "#000000";

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getType() {
        return "SET_BORDERS";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws Exception {
        BordersConfig cfg = mapper.convertValue(properties, BordersConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        CellRangeAddress area = CellStyles.area(cfg.getRange(), "range");
        BorderStyle style = style(cfg.getStyle());
        Set<String> sides = sides(cfg.getSides());
        // Colouring a border that is being removed would be a contradiction; POI keeps the colour of
        // a NONE border in the file, where it does nothing but bloat the style table.
        XSSFColor color = style == BorderStyle.NONE
                ? null
                : CellStyles.color(cfg.getColor() == null ? DEFAULT_COLOR : cfg.getColor(), "color");

        int touched = CellStyles.apply(workbook, sheet, area, new CellStyles.StyleEdit() {
            @Override
            public String key(int row, int column) {
                Set<Edge> edges = edges(sides, area, row, column);
                // The middle of a block being outlined has no edge of its own to draw.
                return edges.isEmpty() ? null
                        : "borders:" + style + ":" + (color == null ? "-" : color.getARGBHex())
                        + ":" + edges;
            }

            @Override
            public void apply(XSSFCellStyle target, int row, int column) {
                for (Edge edge : edges(sides, area, row, column)) {
                    draw(target, edge, style, color);
                }
            }
        });

        log.info("SET_BORDERS {} on {} of '{}': {} cell(s), sides {}",
                style, area.formatAsString(), sheet.getSheetName(), touched, sides);

        if (style != BorderStyle.NONE) {
            return null;
        }
        // Excel draws the line between two cells from whichever side declares it, so clearing this
        // range cannot clear what the cells just outside it draw inwards. Saying so beats a user
        // finding a leftover line and reporting the action as broken.
        return "borders removed from " + area.formatAsString()
                + "; a line drawn by a neighbouring cell just outside it can remain";
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String range = ActionDescriptions.range(properties, "range");
        String where = range == null ? "the cells" : range;
        BorderStyle style;
        Set<String> sides;
        try {
            style = style(ActionDescriptions.text(properties, "style"));
            sides = sides(ActionDescriptions.text(properties, "sides"));
        } catch (RuntimeException _) {
            // describe() runs over whatever the model sent, including what execute() will reject.
            return ActionDescriptions.verb(tense, "Draw", "Drew") + " borders on " + where
                    + ActionDescriptions.sheetSuffix(properties);
        }

        if (style == BorderStyle.NONE) {
            return ActionDescriptions.verb(tense, "Remove", "Removed") + " the borders from " + where
                    + ActionDescriptions.sheetSuffix(properties);
        }
        return ActionDescriptions.verb(tense, "Draw", "Drew") + " " + reading(sides, weight(style))
                + " on " + where + ActionDescriptions.sheetSuffix(properties);
    }

    /** Which edges of one cell the requested sides come down to. */
    private Set<Edge> edges(Set<String> sides, CellRangeAddress area, int row, int column) {
        Set<Edge> edges = EnumSet.noneOf(Edge.class);
        boolean firstRow = row == area.getFirstRow();
        boolean lastRow = row == area.getLastRow();
        boolean firstColumn = column == area.getFirstColumn();
        boolean lastColumn = column == area.getLastColumn();

        for (String side : sides) {
            switch (side) {
                case "all" -> edges.addAll(EnumSet.allOf(Edge.class));
                case "outline" -> {
                    if (firstRow) edges.add(Edge.TOP);
                    if (lastRow) edges.add(Edge.BOTTOM);
                    if (firstColumn) edges.add(Edge.LEFT);
                    if (lastColumn) edges.add(Edge.RIGHT);
                }
                case "inside" -> {
                    if (!firstRow) edges.add(Edge.TOP);
                    if (!lastRow) edges.add(Edge.BOTTOM);
                    if (!firstColumn) edges.add(Edge.LEFT);
                    if (!lastColumn) edges.add(Edge.RIGHT);
                }
                case "top" -> {
                    if (firstRow) edges.add(Edge.TOP);
                }
                case "bottom" -> {
                    if (lastRow) edges.add(Edge.BOTTOM);
                }
                case "left" -> {
                    if (firstColumn) edges.add(Edge.LEFT);
                }
                case "right" -> {
                    if (lastColumn) edges.add(Edge.RIGHT);
                }
                default -> throw new IllegalArgumentException("Unknown side \"" + side
                        + "\" — use all, outline, inside, top, bottom, left or right.");
            }
        }
        return edges;
    }

    private void draw(XSSFCellStyle style, Edge edge, BorderStyle weight, XSSFColor color) {
        switch (edge) {
            case TOP -> {
                style.setBorderTop(weight);
                if (color != null) style.setTopBorderColor(color);
            }
            case BOTTOM -> {
                style.setBorderBottom(weight);
                if (color != null) style.setBottomBorderColor(color);
            }
            case LEFT -> {
                style.setBorderLeft(weight);
                if (color != null) style.setLeftBorderColor(color);
            }
            case RIGHT -> {
                style.setBorderRight(weight);
                if (color != null) style.setRightBorderColor(color);
            }
        }
    }

    /** Defaults to every side: "add borders" with nothing further said means the whole grid. */
    private Set<String> sides(String raw) {
        String cleaned = CellStyles.keyword(raw);
        if (cleaned == null) {
            return Set.of("all");
        }
        Set<String> sides = new LinkedHashSet<>();
        for (String side : cleaned.split(",")) {
            String token = side.trim();
            if (!token.isEmpty()) {
                sides.add(alias(token));
            }
        }
        if (sides.isEmpty()) {
            throw new IllegalArgumentException("\"sides\" named nothing — use all, outline, inside,"
                    + " top, bottom, left or right, or omit it for all.");
        }
        return sides;
    }

    private String alias(String side) {
        return switch (side) {
            case "box", "border", "around", "perimeter" -> "outline";
            case "everything", "grid" -> "all";
            case "internal", "inner" -> "inside";
            default -> side;
        };
    }

    private BorderStyle style(String raw) {
        String cleaned = CellStyles.keyword(raw);
        if (cleaned == null) {
            return BorderStyle.THIN;
        }
        return switch (cleaned) {
            case "thin", "hairline" -> BorderStyle.THIN;
            case "hair" -> BorderStyle.HAIR;
            case "medium" -> BorderStyle.MEDIUM;
            case "thick" -> BorderStyle.THICK;
            case "double" -> BorderStyle.DOUBLE;
            case "dashed" -> BorderStyle.DASHED;
            case "dotted" -> BorderStyle.DOTTED;
            case "none", "remove", "off" -> BorderStyle.NONE;
            default -> throw new IllegalArgumentException("Unknown border style \"" + raw
                    + "\" — use thin, medium, thick, double, dashed, dotted or none.");
        };
    }

    private String weight(BorderStyle style) {
        return switch (style) {
            case THICK -> "thick";
            case MEDIUM -> "medium";
            case DOUBLE -> "double";
            case DASHED -> "dashed";
            case DOTTED -> "dotted";
            default -> "thin";
        };
    }

    /** The weight belongs inside the phrase — "a thick border around the outside", not "thick a border". */
    private String reading(Set<String> sides, String weight) {
        if (sides.contains("all")) {
            return weight + " borders around every cell";
        }
        if (sides.size() == 1) {
            return switch (sides.iterator().next()) {
                case "outline" -> "a " + weight + " border around the outside";
                case "inside" -> weight + " borders between the cells";
                case "top" -> "a " + weight + " line above";
                case "bottom" -> "a " + weight + " line underneath";
                case "left" -> "a " + weight + " line down the left";
                default -> "a " + weight + " line down the right";
            };
        }
        return weight + " borders on " + String.join(" and ", sides);
    }
}
