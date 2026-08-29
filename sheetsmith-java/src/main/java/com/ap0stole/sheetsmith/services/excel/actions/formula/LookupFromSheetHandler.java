package com.ap0stole.sheetsmith.services.excel.actions.formula;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.formula.LookupConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.CellStyles;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pulls a column across from another sheet by matching a key — the "add the price to each order
 * line" job, which is a column of VLOOKUPs and a plan card nobody wants to read 500 times.
 * <p>
 * Three decisions are baked in because each of them is a way to be quietly wrong:
 * <ul>
 *   <li><b>The match is always exact.</b> VLOOKUP's approximate mode returns the nearest smaller
 *       key on unsorted data and looks like it worked, which is the single most productive source
 *       of wrong spreadsheets there is.</li>
 *   <li><b>The formula is built from IF/ISNA rather than IFNA.</b> POI parses {@code IFNA} happily
 *       but does not know it: it is a post-2007 function, which Excel stores with an {@code _xlfn.}
 *       prefix and reads as an unknown defined name without one — a file full of {@code #NAME?}.
 *       {@code IF}, {@code ISNA} and {@code VLOOKUP} are all in POI's own function table, so the
 *       result is a formula POI can evaluate and Excel can open, which the tests check by
 *       evaluating it.</li>
 *   <li><b>Misses are counted here, not left to the sheet.</b> Rows with no match are blanked by
 *       default rather than left as {@code #N/A}, because a column of errors breaks every SUM
 *       downstream — and the count is reported instead, so nothing is hidden.</li>
 * </ul>
 */
@Slf4j
@Component
public class LookupFromSheetHandler implements ActionHandler {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getType() {
        return "LOOKUP_FROM_SHEET";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws Exception {
        LookupConfig cfg = mapper.convertValue(properties, LookupConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        CellRangeAddress target = singleColumn(cfg.getRange(), "range");
        CellRangeAddress keys = singleColumn(cfg.getKeyRange(), "keyRange");

        int height = target.getLastRow() - target.getFirstRow() + 1;
        if (keys.getLastRow() - keys.getFirstRow() + 1 != height) {
            throw new IllegalArgumentException("\"keyRange\" and \"range\" have to cover the same rows"
                    + " — " + keys.formatAsString() + " is " + (keys.getLastRow() - keys.getFirstRow() + 1)
                    + " rows and " + target.formatAsString() + " is " + height + ".");
        }

        XSSFSheet source = sourceSheet(workbook, sheet, cfg);
        CellRangeAddress lookup = CellStyles.area(stripSheet(cfg.getSourceRange()), "sourceRange");
        int column = columnIndex(cfg.getSourceColumn(), lookup);

        String reference = quotedSheet(source.getSheetName()) + "!" + absolute(lookup);
        String keyColumn = ActionDescriptions.columnLetter(keys.getFirstColumn());
        String fallback = fallback(cfg.getIfMissing());

        Set<String> available = keysIn(source, lookup);
        int missing = 0;

        for (int i = 0; i < height; i++) {
            int keyRow = keys.getFirstRow() + i;
            int targetRow = target.getFirstRow() + i;

            String key = "$" + keyColumn + (keyRow + 1);
            String vlookup = "VLOOKUP(" + key + "," + reference + "," + column + ",FALSE)";
            String formula = fallback == null
                    ? vlookup
                    : "IF(ISNA(" + vlookup + ")," + fallback + "," + vlookup + ")";

            cell(sheet, targetRow, target.getFirstColumn()).setCellFormula(formula);

            String token = token(cellAt(sheet, keyRow, keys.getFirstColumn()));
            if (token == null || !available.contains(token)) {
                missing++;
            }
        }

        log.info("LOOKUP_FROM_SHEET wrote {} lookups into {} against '{}' ({} unmatched)",
                height, target.formatAsString(), source.getSheetName(), missing);

        if (missing == 0) {
            return null;
        }
        return missing + " of " + height + " keys " + (missing == 1 ? "has" : "have")
                + " no match on \"" + source.getSheetName() + "\""
                + (fallback == null ? ", and those rows will show #N/A" : ", and those rows stay blank");
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String range = ActionDescriptions.range(properties, "range");
        String keys = ActionDescriptions.range(properties, "keyRange");
        String source = ActionDescriptions.text(properties, "sourceRange");
        String sourceSheet = ActionDescriptions.text(properties, "sourceSheet");
        String column = ActionDescriptions.text(properties, "sourceColumn");

        String from = source == null
                ? (sourceSheet == null ? "another sheet" : ActionDescriptions.quoted(sourceSheet))
                : source + (sourceSheet == null ? "" : " on " + ActionDescriptions.quoted(sourceSheet));

        return ActionDescriptions.verb(tense, "Fill", "Filled") + " "
                + (range == null ? "the column" : range) + " from " + from
                + (column == null ? "" : ", column " + column)
                + (keys == null ? "" : ", matching on " + keys)
                + ActionDescriptions.sheetSuffix(properties);
    }

    /** A lookup writes down one column; two columns at once would need two different formulas. */
    private CellRangeAddress singleColumn(String raw, String key) {
        CellRangeAddress area = CellStyles.area(raw, key);
        if (area.getFirstColumn() != area.getLastColumn()) {
            throw new IllegalArgumentException("\"" + key + "\" has to be one column, but "
                    + area.formatAsString() + " covers "
                    + (area.getLastColumn() - area.getFirstColumn() + 1) + " — do one column per step.");
        }
        return area;
    }

    private XSSFSheet sourceSheet(XSSFWorkbook workbook, XSSFSheet current, LookupConfig cfg) {
        String named = cfg.getSourceSheet();
        if (named == null || named.isBlank()) {
            named = sheetPrefix(cfg.getSourceRange());
        }
        if (named == null || named.isBlank()) {
            // Looking up within the same sheet is legitimate — a key column and a table beside it.
            return current;
        }
        XSSFSheet found = workbook.getSheet(named);
        if (found == null) {
            throw new IllegalArgumentException("Sheet not found: \"" + named + "\" — name the sheet"
                    + " holding the table, either in \"sourceSheet\" or as a prefix like"
                    + " \"Products!A2:C100\".");
        }
        return found;
    }

    /** Either a position inside the range, or the letter of a real column that has to sit in it. */
    private int columnIndex(String raw, CellRangeAddress lookup) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("\"sourceColumn\" says which column to bring back —"
                    + " either its letter (e.g. \"C\") or its position in \"sourceRange\" (e.g. 3).");
        }
        String cleaned = raw.trim();
        int width = lookup.getLastColumn() - lookup.getFirstColumn() + 1;
        int index;

        if (cleaned.matches("\\d+")) {
            index = Integer.parseInt(cleaned);
        } else if (cleaned.matches("(?i)[a-z]{1,3}")) {
            int absolute = org.apache.poi.ss.util.CellReference.convertColStringToIndex(
                    cleaned.toUpperCase(Locale.ROOT));
            index = absolute - lookup.getFirstColumn() + 1;
        } else {
            throw new IllegalArgumentException("\"sourceColumn\" \"" + raw + "\" is neither a column"
                    + " letter nor a position — use \"C\" or 3.");
        }

        if (index < 1 || index > width) {
            throw new IllegalArgumentException("\"sourceColumn\" \"" + raw + "\" is outside"
                    + " \"sourceRange\" " + lookup.formatAsString() + ", which is " + width
                    + " column(s) wide — VLOOKUP counts from its first column.");
        }
        return index;
    }

    /** What a row with no match shows; null means the bare VLOOKUP, errors and all. */
    private String fallback(String ifMissing) {
        if (ifMissing == null) {
            return "\"\"";
        }
        String cleaned = ifMissing.trim();
        if (cleaned.equalsIgnoreCase("#N/A") || cleaned.equalsIgnoreCase("error")
                || cleaned.equalsIgnoreCase("na")) {
            return null;
        }
        return "\"" + cleaned.replace("\"", "\"\"") + "\"";
    }

    /** The keys the source table can actually match, typed the way Excel compares them. */
    private Set<String> keysIn(XSSFSheet source, CellRangeAddress lookup) {
        Set<String> keys = new HashSet<>();
        for (int r = lookup.getFirstRow(); r <= lookup.getLastRow(); r++) {
            String token = token(cellAt(source, r, lookup.getFirstColumn()));
            if (token != null) {
                keys.add(token);
            }
        }
        return keys;
    }

    /**
     * A comparable form of a cell's value. Text and numbers are kept apart because Excel keeps them
     * apart — the string "42" does not match the number 42 — while text matches case-insensitively,
     * which is what VLOOKUP does.
     */
    private String token(Cell cell) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case NUMERIC -> "n:" + cell.getNumericCellValue();
            case BOOLEAN -> "b:" + cell.getBooleanCellValue();
            case STRING -> {
                String value = cell.getStringCellValue().trim();
                yield value.isEmpty() ? null : "s:" + value.toLowerCase(Locale.ROOT);
            }
            default -> null;
        };
    }

    private Cell cellAt(XSSFSheet sheet, int row, int column) {
        XSSFRow r = sheet.getRow(row);
        return r == null ? null : r.getCell(column);
    }

    private XSSFCell cell(XSSFSheet sheet, int row, int column) {
        XSSFRow r = sheet.getRow(row);
        if (r == null) {
            r = sheet.createRow(row);
        }
        XSSFCell cell = r.getCell(column);
        return cell == null ? r.createCell(column) : cell;
    }

    /** Absolute, so every row of the filled column looks at the same table. */
    private String absolute(CellRangeAddress area) {
        return "$" + ActionDescriptions.columnLetter(area.getFirstColumn()) + "$" + (area.getFirstRow() + 1)
                + ":$" + ActionDescriptions.columnLetter(area.getLastColumn()) + "$" + (area.getLastRow() + 1);
    }

    private String quotedSheet(String name) {
        return name.matches("\\w+") ? name : "'" + name.replace("'", "''") + "'";
    }

    private String sheetPrefix(String raw) {
        if (raw == null) {
            return null;
        }
        int bang = raw.lastIndexOf('!');
        if (bang < 0) {
            return null;
        }
        return raw.substring(0, bang).trim().replaceAll("(?:^')|(?:'$)", "").replace("''", "'");
    }

    private String stripSheet(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("\"sourceRange\" is required — the table to look in,"
                    + " e.g. \"Products!A2:C100\".");
        }
        return raw.substring(raw.lastIndexOf('!') + 1);
    }
}
