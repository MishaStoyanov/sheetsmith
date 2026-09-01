package com.ap0stole.sheetsmith.services.excel.actions.format;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.format.AlignCellsConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.CellStyles;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.io.IOException;

/**
 * Where a value sits in its cell, and whether a long one wraps instead of running off the edge.
 * <p>
 * Centring a header row is the request; wrapping is the one that carries a trap. Excel grows a row
 * to fit wrapped text only while that row's height is automatic, and a row whose height was ever set
 * by hand — which every row in a file that has been edited in Excel may be — keeps that height and
 * clips the text instead. So turning wrapping on also returns those rows to automatic height, and
 * says how many it had to.
 * <p>
 * Every key is optional but at least one is required: an alignment step that names nothing has
 * nothing to do, and doing nothing silently is how a plan grows steps that only look like work.
 */
@Slf4j
@Component
public class AlignCellsHandler implements ActionHandler {

    // The spellings a person or a model might use for the same alignment, named because the
    // describing switch and the applying switch have to recognise the same set — and a synonym
    // added to one of them is a step that reads as one thing and does another.
    private static final String CENTER = "center";
    private static final String CENTRE = "centre";
    private static final String MIDDLE = "middle";
    private static final String JUSTIFY = "justify";
    private static final String JUSTIFIED = "justified";

    /** Excel's own ceiling on indent steps. */
    private static final int MAX_INDENT = 15;

    /** POI's marker for "let Excel decide this row's height". */
    private static final short AUTOMATIC_HEIGHT = -1;

    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Override
    public String getType() {
        return "ALIGN_CELLS";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws IOException {
        AlignCellsConfig cfg = mapper.convertValue(properties, AlignCellsConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        CellRangeAddress area = CellStyles.area(cfg.getRange(), "range");

        HorizontalAlignment horizontal = horizontal(cfg.getHorizontal());
        VerticalAlignment vertical = vertical(cfg.getVertical());
        Boolean wrap = cfg.getWrapText();
        Integer indent = indent(cfg.getIndent());

        if (horizontal == null && vertical == null && wrap == null && indent == null) {
            throw new IllegalArgumentException("Nothing to align — name at least one of"
                    + " \"horizontal\" (left, center, right, justify), \"vertical\" (top, middle,"
                    + " bottom), \"wrapText\" or \"indent\".");
        }

        String variant = "align:" + horizontal + ":" + vertical + ":" + wrap + ":" + indent;
        int touched = CellStyles.apply(workbook, sheet, area, new CellStyles.StyleEdit() {
            @Override
            public String key(int row, int column) {
                return variant;
            }

            @Override
            public void apply(XSSFCellStyle style, int row, int column) {
                if (horizontal != null) {
                    style.setAlignment(horizontal);
                }
                if (vertical != null) {
                    style.setVerticalAlignment(vertical);
                }
                if (wrap != null) {
                    style.setWrapText(wrap);
                }
                if (indent != null) {
                    style.setIndention(indent.shortValue());
                }
            }
        });

        List<Integer> freed = Boolean.TRUE.equals(wrap) ? freeRowHeights(sheet, area) : List.of();

        log.info("ALIGN_CELLS on {} of '{}': {} cell(s), horizontal={}, vertical={}, wrap={},"
                        + " indent={}, {} row height(s) returned to automatic",
                area.formatAsString(), sheet.getSheetName(), touched, horizontal, vertical, wrap,
                indent, freed.size());

        if (freed.isEmpty()) {
            return null;
        }
        return freed.size() + (freed.size() == 1 ? " row had a fixed height" : " rows had fixed heights")
                + " that would have clipped the wrapped text, so "
                + (freed.size() == 1 ? "it was" : "they were") + " returned to automatic height";
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String range = ActionDescriptions.range(properties, "range");
        String where = range == null ? "the cells" : range;
        List<String> parts = new ArrayList<>();

        String horizontal = CellStyles.keyword(ActionDescriptions.text(properties, "horizontal"));
        String vertical = CellStyles.keyword(ActionDescriptions.text(properties, "vertical"));
        if (horizontal != null) {
            parts.add(switch (horizontal) {
                case CENTER, CENTRE, MIDDLE -> "centred";
                case "right" -> "right-aligned";
                case "left" -> "left-aligned";
                case JUSTIFY -> JUSTIFIED;
                default -> horizontal;
            });
        }
        if (vertical != null) {
            parts.add(switch (vertical) {
                case "top" -> "aligned to the top";
                case "bottom" -> "aligned to the bottom";
                case MIDDLE, CENTER, CENTRE -> "vertically centred";
                default -> vertical;
            });
        }
        // A wrap that was asked to be off reads as its own thing, not as a kind of alignment.
        Boolean wrap = properties != null && properties.containsKey("wrapText")
                ? ActionDescriptions.flag(properties, "wrapText", true) : null;
        if (Boolean.TRUE.equals(wrap)) {
            parts.add("wrapped");
        } else if (Boolean.FALSE.equals(wrap)) {
            parts.add("unwrapped");
        }
        Integer indent = ActionDescriptions.integer(properties, "indent");
        if (indent != null && indent > 0) {
            parts.add("indented");
        }

        String what = parts.isEmpty() ? "aligned" : String.join(" and ", parts);
        return ActionDescriptions.verb(tense, "Align", "Aligned") + " " + where + " — " + what
                + ActionDescriptions.sheetSuffix(properties);
    }

    /**
     * Rows in the range whose height was pinned by hand, returned to automatic so wrapped text can
     * make room for itself.
     *
     * @return the 1-based rows that were changed
     */
    private List<Integer> freeRowHeights(XSSFSheet sheet, CellRangeAddress area) {
        List<Integer> freed = new ArrayList<>();
        for (int r = area.getFirstRow(); r <= area.getLastRow(); r++) {
            XSSFRow row = sheet.getRow(r);
            // CellStyles.apply created every row in the range, so null cannot happen here; a hidden
            // row was hidden deliberately and is not this action's to reopen. Only a height Excel
            // marked as custom is worth undoing — the rest already grow on their own.
            if (row == null || row.getZeroHeight() || !row.getCTRow().getCustomHeight()) {
                continue;
            }
            row.setHeight(AUTOMATIC_HEIGHT);
            freed.add(r + 1);
        }
        return freed;
    }

    private HorizontalAlignment horizontal(String raw) {
        String cleaned = CellStyles.keyword(raw);
        if (cleaned == null) {
            return null;
        }
        return switch (cleaned) {
            case "left", "start" -> HorizontalAlignment.LEFT;
            case CENTER, CENTRE, MIDDLE -> HorizontalAlignment.CENTER;
            case "right", "end" -> HorizontalAlignment.RIGHT;
            case JUSTIFY, JUSTIFIED -> HorizontalAlignment.JUSTIFY;
            case "general", "default" -> HorizontalAlignment.GENERAL;
            default -> throw new IllegalArgumentException("Unknown \"horizontal\" alignment \"" + raw
                    + "\" — use left, center, right, justify or general.");
        };
    }

    private VerticalAlignment vertical(String raw) {
        String cleaned = CellStyles.keyword(raw);
        if (cleaned == null) {
            return null;
        }
        return switch (cleaned) {
            case "top" -> VerticalAlignment.TOP;
            case MIDDLE, CENTER, CENTRE -> VerticalAlignment.CENTER;
            case "bottom" -> VerticalAlignment.BOTTOM;
            case JUSTIFY, JUSTIFIED -> VerticalAlignment.JUSTIFY;
            default -> throw new IllegalArgumentException("Unknown \"vertical\" alignment \"" + raw
                    + "\" — use top, middle or bottom.");
        };
    }

    private Integer indent(Integer requested) {
        if (requested == null) {
            return null;
        }
        if (requested < 0 || requested > MAX_INDENT) {
            throw new IllegalArgumentException("\"indent\" has to be between 0 and " + MAX_INDENT
                    + ", but was " + requested + ".");
        }
        return requested;
    }
}
