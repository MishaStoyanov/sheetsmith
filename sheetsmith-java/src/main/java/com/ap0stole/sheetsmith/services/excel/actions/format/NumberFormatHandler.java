package com.ap0stole.sheetsmith.services.excel.actions.format;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.format.NumberFormatConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.CellStyles;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.io.IOException;

/**
 * Changes how numbers read — currency, percent, thousands, dates — without touching the numbers.
 * <p>
 * A spreadsheet's most common complaint is not that a value is wrong but that it shows as
 * {@code 1234.5} where {@code $1,234.50} was meant, or as {@code 46023} where a date was meant.
 * FORMAT_CELLS could colour a cell and TRANSFORM_COLUMN could rewrite its text, but neither could
 * say "these are dollars" and leave the value a number the sheet can still add up.
 * <p>
 * Two things here are more than a call to POI. The named formats exist because a model asked for a
 * raw pattern produces {@code #,##0.00;[Red]-#,##0.00} on a good day and something Excel offers to
 * repair on a bad one, so the names cover the requests that actually arrive and a literal pattern
 * stays available underneath — checked against a real value before it is written, since POI accepts
 * a malformed pattern happily and Excel then refuses the file. And a format applied to text is
 * reported rather than passed off as done: {@code "1234"} stored as a string ignores every number
 * format there is, which would otherwise be a step that claims success and visibly changes nothing.
 */
@Slf4j
@Component
public class NumberFormatHandler implements ActionHandler {

    /** Excel's own ceiling on a format string. */
    private static final int MAX_PATTERN = 255;

    private static final String DEFAULT_CURRENCY = "$";

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final DataFormatter formatter = new DataFormatter();

    @Override
    public String getType() {
        return "NUMBER_FORMAT";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws IOException {
        NumberFormatConfig cfg = mapper.convertValue(properties, NumberFormatConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        CellRangeAddress area = CellStyles.area(cfg.getRange(), "range");
        String pattern = pattern(cfg);
        short format = workbook.getCreationHelper().createDataFormat().getFormat(pattern);

        int text = countText(sheet, area, pattern);
        int touched = CellStyles.apply(workbook, sheet, area, new CellStyles.StyleEdit() {
            @Override
            public String key(int row, int column) {
                return "format:" + format;
            }

            @Override
            public void apply(XSSFCellStyle style, int row, int column) {
                style.setDataFormat(format);
            }
        });

        log.info("NUMBER_FORMAT applied \"{}\" to {} on '{}': {} cell(s), {} holding text",
                pattern, area.formatAsString(), sheet.getSheetName(), touched, text);

        if (text == 0) {
            return null;
        }
        // Nothing failed, so nothing raises an error — and the sheet looks exactly as it did, which
        // is precisely the outcome a user would report as the action not working.
        return text + (text == 1 ? " cell holds text" : " cells hold text")
                + ", where a number format shows nothing — use TRANSFORM_COLUMN to turn"
                + " those into numbers first";
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String range = ActionDescriptions.range(properties, "range");
        String format = ActionDescriptions.text(properties, "format");
        return ActionDescriptions.verb(tense, "Show", "Showed") + " "
                + (range == null ? "the cells" : range) + " as " + reading(format, properties)
                + ActionDescriptions.sheetSuffix(properties);
    }

    /** The plan card says what the user will see, so a named format reads as its name, not its pattern. */
    private String reading(String format, Map<String, Object> properties) {
        String named = CellStyles.keyword(format);
        if (named == null) {
            return "formatted numbers";
        }
        Integer decimals = ActionDescriptions.integer(properties, "decimals");
        String places = decimals == null ? "" : " to " + decimals
                + (decimals == 1 ? " decimal place" : " decimal places");
        return switch (named) {
            case "currency", "money" -> "currency" + places;
            case "percent", "percentage" -> "percentages" + places;
            case "thousands", "integer", "number", "decimal" -> "numbers with thousands separators" + places;
            case "date" -> "dates";
            case "datetime", "date-time" -> "dates with times";
            case "time" -> "times";
            case "scientific" -> "scientific notation" + places;
            case "text" -> "plain text";
            case "general", "plain", "none" -> "unformatted values";
            // A literal Excel pattern is not a sentence; quoting it is the honest reading.
            default -> "the format " + ActionDescriptions.quoted(format);
        };
    }

    /**
     * The Excel pattern to write. A name resolves to one, and anything else is taken literally —
     * after being run over a sample value, because POI stores a malformed pattern without complaint
     * and the corruption only surfaces when Excel opens the file.
     */
    private String pattern(NumberFormatConfig cfg) {
        String named = CellStyles.keyword(cfg.getFormat());
        if (named == null) {
            throw new IllegalArgumentException("\"format\" is required — a name like \"currency\","
                    + " \"percent\", \"thousands\", \"date\" or a literal Excel pattern like"
                    + " \"#,##0.00\".");
        }

        int decimals = decimals(cfg.getDecimals());
        String symbol = cfg.getCurrencySymbol() == null || cfg.getCurrencySymbol().isBlank()
                ? DEFAULT_CURRENCY : cfg.getCurrencySymbol().trim();

        String resolved = switch (named) {
            case "currency", "money" -> "\"" + symbol + "\"#,##0" + places(cfg.getDecimals() == null ? 2 : decimals);
            case "percent", "percentage" -> "0" + places(cfg.getDecimals() == null ? 0 : decimals) + "%";
            case "thousands", "number", "decimal" -> "#,##0" + places(cfg.getDecimals() == null ? 2 : decimals);
            case "integer", "whole" -> "#,##0";
            case "scientific" -> "0" + places(cfg.getDecimals() == null ? 2 : decimals) + "E+00";
            case "date" -> "yyyy-mm-dd";
            case "datetime", "date-time" -> "yyyy-mm-dd hh:mm:ss";
            case "time" -> "hh:mm:ss";
            case "text" -> "@";
            case "general", "plain", "none" -> "General";
            default -> literal(cfg.getFormat().trim());
        };
        log.debug("NUMBER_FORMAT resolved \"{}\" to \"{}\"", cfg.getFormat(), resolved);
        return resolved;
    }

    private String literal(String raw) {
        if (raw.length() > MAX_PATTERN) {
            throw new IllegalArgumentException("\"format\" is longer than the " + MAX_PATTERN
                    + " characters Excel allows in a format pattern.");
        }
        try {
            // Both a number and a date, because a pattern valid for one can be nonsense for the
            // other, and this is the only chance to say so before the file reaches Excel.
            formatter.formatRawCellContents(1234.5678, -1, raw);
        } catch (RuntimeException _) {
            throw new IllegalArgumentException("\"" + raw + "\" is not a usable Excel number format"
                    + " — try a name like \"currency\" or \"percent\", or a pattern like"
                    + " \"#,##0.00\".");
        }
        return raw;
    }

    private int decimals(Integer requested) {
        if (requested == null) {
            return 2;
        }
        if (requested < 0 || requested > 10) {
            throw new IllegalArgumentException("\"decimals\" has to be between 0 and 10, but was "
                    + requested + ".");
        }
        return requested;
    }

    private String places(int decimals) {
        return decimals == 0 ? "" : "." + "0".repeat(decimals);
    }

    /**
     * Cells holding text, which no number format can reach. Only counted when the format is one
     * that formats numbers: {@code "@"} is the format for text, and complaining about text there
     * would be complaining that the action worked.
     */
    private int countText(XSSFSheet sheet, CellRangeAddress area, String pattern) {
        if ("@".equals(pattern) || "General".equals(pattern)) {
            return 0;
        }
        int text = 0;
        for (int r = area.getFirstRow(); r <= area.getLastRow(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            for (int c = area.getFirstColumn(); c <= area.getLastColumn(); c++) {
                Cell cell = row.getCell(c);
                if (cell != null && cell.getCellType() == CellType.STRING
                        && !cell.getStringCellValue().isBlank()) {
                    text++;
                }
            }
        }
        return text;
    }
}
