package com.ap0stole.sheetsmith.services.excel.actions.cell;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.cell.SetCellValueConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.CellValues;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes a literal value into a cell, or the same literal into every cell of a small range.
 * <p>
 * The engine could already write a formula and rewrite a column wholesale, but had no way to put a
 * plain value into an empty cell — "write Q1 2026 in A1" was simply not expressible.
 * <p>
 * Two rules carry most of the weight. A quoted value becomes a number only when the number renders
 * back to exactly the string that was sent, so {@code "007"} stays the text a user wanted kept while
 * {@code "42"} still lands as a number. And nothing here replaces a cell style: the value changes,
 * the formatting the cell already had does not.
 */
@Slf4j
@Component
public class SetCellValueHandler implements ActionHandler {

    /**
     * Ceiling on a single fill. Far above "write a label", far below a bulk rewrite — which is
     * TRANSFORM_COLUMN's job, and which the error below points at.
     */
    private static final int MAX_CELLS = 10_000;

    private static final String DATE_FORMAT = "yyyy-mm-dd";
    private static final String DATE_TIME_FORMAT = "yyyy-mm-dd hh:mm:ss";

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getType() {
        return "SET_CELL_VALUE";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws Exception {
        SetCellValueConfig cfg = mapper.convertValue(properties, SetCellValueConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        CellRangeAddress area = target(cfg);
        Object typed = resolve(cfg.getValue(), cfg.getValueType());

        long cells = (long) (area.getLastRow() - area.getFirstRow() + 1)
                * (area.getLastColumn() - area.getFirstColumn() + 1);
        if (cells > MAX_CELLS) {
            // TRANSFORM_COLUMN is named only for what it can actually do: it rewrites values a column
            // already has and skips blanks, so it is no help at all for the case that trips this cap.
            throw new IllegalArgumentException(area.formatAsString() + " covers " + cells
                    + " cells, limit is " + MAX_CELLS + " — split the fill into steps of "
                    + MAX_CELLS + " cells or fewer. (To rewrite values the column already holds,"
                    + " use TRANSFORM_COLUMN instead.)");
        }

        // Deriving a date style per cell would exhaust the workbook's style table on a large fill.
        Map<String, CellStyle> dateStyles = new HashMap<>();
        // Only the regions actually overlapping the target matter, and most sheets contribute none —
        // which turns the per-cell merge test into a single isEmpty() check.
        List<CellRangeAddress> merged = sheet.getMergedRegions().stream()
                .filter(region -> region.intersects(area))
                .toList();

        int written = 0;
        int hidden = 0;
        int formulas = 0;
        for (int r = area.getFirstRow(); r <= area.getLastRow(); r++) {
            for (int c = area.getFirstColumn(); c <= area.getLastColumn(); c++) {
                if (!merged.isEmpty() && swallowedByMerge(merged, r, c)) {
                    hidden++;
                    continue;
                }
                if (write(workbook, cellAt(sheet, r, c), typed, dateStyles)) {
                    formulas++;
                }
                written++;
            }
        }

        if (written == 0) {
            throw new IllegalArgumentException(area.formatAsString()
                    + " lies inside a merged region, so writing there would show nothing"
                    + " — target the region's top-left cell instead.");
        }

        log.info("SET_CELL_VALUE wrote {} into {} on '{}': {} cell(s), {} hidden by a merge,"
                + " {} formula(s) replaced", typed, area.formatAsString(), sheet.getSheetName(),
                written, hidden, formulas);

        return detail(written, hidden, formulas);
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String where = ActionDescriptions.range(properties, "cell");
        if (where == null) {
            where = ActionDescriptions.range(properties, "range");
        }
        String value = ActionDescriptions.text(properties, "value");

        StringBuilder text = new StringBuilder(ActionDescriptions.verb(tense, "Write", "Wrote"))
                .append(' ')
                .append(value == null ? "a value" : bare(properties) ? value : ActionDescriptions.quoted(value))
                .append(" into ")
                .append(where == null ? "the cell" : where);
        return text.append(ActionDescriptions.sheetSuffix(properties)).toString();
    }

    /**
     * Whether the value should read without quotes — decided by asking {@link #resolve} what will
     * actually be written, so the sentence cannot claim a number where a text cell is coming. A
     * number or a boolean reads wrong quoted; text and dates read wrong bare.
     */
    private boolean bare(Map<String, Object> properties) {
        try {
            Object raw = properties == null ? null : properties.get("value");
            Object resolved = resolve(raw, ActionDescriptions.text(properties, "valueType"));
            return resolved instanceof Double || resolved instanceof Boolean;
        } catch (RuntimeException e) {
            // describe() runs over whatever the model sent, including values execute() will reject.
            return false;
        }
    }

    /**
     * Silence unless there is something the sentence above did not already say.
     * <p>
     * A replaced formula always counts as something: describe() says only what was written, so
     * without this the plan card and the chat chain would never mention that a calculation the user
     * had is gone — and a deletion raises no formula error for the self-check to notice either.
     */
    private String detail(int written, int hidden, int formulas) {
        if (written == 1 && hidden == 0 && formulas == 0) {
            return null;
        }
        StringBuilder text = new StringBuilder(written + (written == 1 ? " cell set" : " cells set"));
        if (formulas > 0) {
            text.append(written == 1 && formulas == 1
                    ? ", replacing the formula it held"
                    : ", replacing " + formulas + (formulas == 1 ? " formula" : " formulas"));
        }
        if (hidden > 0) {
            text.append(", ").append(hidden)
                    .append(" left alone (inside a merged region, where the value would not show)");
        }
        return text.toString();
    }

    /**
     * The cells to write into. A whole-column or whole-row reference is refused rather than passed
     * on: POI resolves {@code "A:A"} to first row -1 and {@code "1:1"} to first column -1, which
     * survives a size check and only fails later inside the write.
     */
    private CellRangeAddress target(SetCellValueConfig cfg) {
        String raw = cfg.getCell() != null && !cfg.getCell().isBlank() ? cfg.getCell() : cfg.getRange();
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("\"cell\" is required, e.g. \"A1\".");
        }

        CellRangeAddress area = CellRangeAddress.valueOf(
                raw.substring(raw.lastIndexOf('!') + 1).replace("$", "").trim());
        if (area.getFirstRow() < 0 || area.getFirstColumn() < 0) {
            throw new IllegalArgumentException("\"" + raw + "\" names a whole "
                    + (area.getFirstRow() < 0 ? "column" : "row")
                    + " — name a cell like \"A1\", or a bounded range like \"A1:A20\".");
        }
        return area;
    }

    /**
     * The literal to write, as a Double, Boolean, LocalDate, LocalDateTime or String.
     * <p>
     * With no {@code valueType} the JSON type decides, and a string stays a string unless the number
     * it parses to renders back identically — the test that keeps {@code "007"} out of the numeric
     * column. Dates are never guessed: a date-looking string is text until someone says otherwise,
     * because a mis-parsed date is silent corruption and an unformatted one shows as a serial number.
     */
    private Object resolve(Object value, String valueType) {
        if (value == null) {
            throw new IllegalArgumentException("\"value\" is required — the literal to write.");
        }
        String text = String.valueOf(value);
        String type = valueType == null ? "auto" : valueType.trim().toLowerCase(Locale.ROOT);

        return switch (type) {
            case "auto", "" -> infer(value, text);
            case "text", "string" -> text;
            case "number", "numeric" -> number(text);
            case "boolean", "bool" -> bool(text);
            case "date", "datetime" -> date(text);
            default -> throw new IllegalArgumentException("Unknown \"valueType\" \"" + valueType
                    + "\" — use text, number, boolean or date.");
        };
    }

    private Object infer(Object value, String text) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
            return Boolean.parseBoolean(text);
        }
        try {
            double parsed = Double.parseDouble(text);
            return String.valueOf(CellValues.tidy(parsed)).equals(text) ? (Object) parsed : text;
        } catch (NumberFormatException e) {
            return text;
        }
    }

    /**
     * POI does not store a non-finite double: {@code setCellValue} turns Infinity into a #DIV/0!
     * and NaN into a #NUM! error cell. Left through, the step would report a value written and
     * instead plant a fresh formula error for the chat's own repair pass to chase.
     */
    private Double number(String text) {
        double parsed;
        try {
            parsed = Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\"" + text + "\" is not a number.");
        }
        if (!Double.isFinite(parsed)) {
            throw new IllegalArgumentException("\"" + text + "\" is not a finite number.");
        }
        return parsed;
    }

    private Boolean bool(String text) {
        String trimmed = text.trim();
        if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
            return Boolean.parseBoolean(trimmed);
        }
        throw new IllegalArgumentException("\"" + text + "\" is not true or false.");
    }

    /** ISO-8601 only. Every other notation is ambiguous about which of 01/02 is the month. */
    private Object date(String text) {
        String trimmed = text.trim();
        try {
            return LocalDate.parse(trimmed);
        } catch (DateTimeParseException ignored) {
            // Not a plain date; a date-time is the only other form accepted.
        }
        try {
            return LocalDateTime.parse(trimmed);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("\"" + text
                    + "\" is not an ISO date — use \"2026-01-31\" or \"2026-01-31T14:30:00\".");
        }
    }

    private Cell cellAt(XSSFSheet sheet, int rowIdx, int colIdx) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) row = sheet.createRow(rowIdx);
        Cell cell = row.getCell(colIdx);
        return cell == null ? row.createCell(colIdx) : cell;
    }

    /** @return whether a formula was replaced, which the step has to report. */
    private boolean write(XSSFWorkbook workbook, Cell cell, Object typed, Map<String, CellStyle> dateStyles) {
        // setCellValue on a formula cell only overwrites the cached result and leaves the formula
        // in place, so the value the user asked for would vanish on the next recalculation.
        boolean replacedFormula = cell.getCellType() == CellType.FORMULA;
        if (replacedFormula) {
            try {
                cell.setBlank();
            } catch (IllegalStateException e) {
                // Excel-authored files carry multi-cell array formulas, which POI refuses to break up
                // one cell at a time — and its own message is not something to show a user.
                throw new IllegalArgumentException(new CellAddress(cell).formatAsString()
                        + " is part of an array formula covering several cells, which cannot be"
                        + " overwritten one cell at a time — clear the whole formula first, or write"
                        + " somewhere else.");
            }
        }
        switch (typed) {
            case Double d -> cell.setCellValue(d);
            case Boolean b -> cell.setCellValue(b);
            case LocalDate d -> {
                cell.setCellValue(d);
                applyDateFormat(workbook, cell, DATE_FORMAT, dateStyles);
            }
            case LocalDateTime dt -> {
                cell.setCellValue(dt);
                applyDateFormat(workbook, cell, DATE_TIME_FORMAT, dateStyles);
            }
            default -> cell.setCellValue(String.valueOf(typed));
        }
        return replacedFormula;
    }

    /**
     * A date is a number to Excel, so without a date format the cell reads 46023. The style is
     * cloned rather than replaced, because the cell's colours and borders are not ours to discard.
     * <p>
     * Keyed on the source style *and* the pattern: today one step writes one value and so needs one
     * pattern, but keying on the style alone would hand a date-time cell the date-only format the
     * moment that stops being true.
     */
    private void applyDateFormat(XSSFWorkbook workbook, Cell cell, String pattern, Map<String, CellStyle> cache) {
        CellStyle current = cell.getCellStyle();
        if (alreadyDated(current)) {
            return;
        }
        cell.setCellStyle(cache.computeIfAbsent(current.getIndex() + "|" + pattern, key -> {
            CellStyle dated = workbook.createCellStyle();
            dated.cloneStyleFrom(current);
            dated.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat(pattern));
            return dated;
        }));
    }

    /** A cell already showing dates keeps the format its owner chose. */
    private boolean alreadyDated(CellStyle style) {
        try {
            return DateUtil.isADateFormat(style.getDataFormat(), style.getDataFormatString());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean swallowedByMerge(List<CellRangeAddress> merged, int row, int col) {
        for (CellRangeAddress region : merged) {
            if (region.isInRange(row, col)
                    && !(region.getFirstRow() == row && region.getFirstColumn() == col)) {
                return true;
            }
        }
        return false;
    }
}
