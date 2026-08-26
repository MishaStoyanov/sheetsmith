package com.ap0stole.sheetsmith.services.excel.actions;

import com.ap0stole.sheetsmith.configs.ProcessingConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.ap0stole.sheetsmith.services.excel.model.AutosizeColumnsConfig;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Widens columns to fit what is in them.
 * <p>
 * Four things make this more than a one-line call to POI. It measures every value in a column through
 * AWT, so the cost follows the size of the sheet: the step sizes columns until
 * {@code xlsxai.processing.max-autosize-cells} is spent and then reports the rest as skipped, rather
 * than refusing outright — the bound is per step, so a refusal only teaches the model to split the
 * range and do the same total work. That same measurement is the only thing here that needs fonts,
 * which a slim JRE container may not have, so a column that cannot be measured keeps the width it had
 * and is counted. POI leaves an empty column entirely alone, so a column has to be measured before and
 * after to know whether anything actually moved. And it sizes a formula cell from the cached result,
 * which is stale until the workbook is saved — hence {@link #refreshFormulas}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutosizeColumnsHandler implements ActionHandler {

    /** Excel's own ceiling, in characters. A column may not be wider whatever the content. */
    private static final int EXCEL_MAX_WIDTH = 255;

    /** Wide enough for a long label, narrow enough that a stray paragraph does not swallow the screen. */
    private static final int DEFAULT_MAX_WIDTH = 60;

    /** POI measures column width in 1/256ths of a character. */
    private static final int UNITS_PER_CHARACTER = 256;

    /** POI's own measurement runs slightly narrow, which shows up as a numeric column rendering ###. */
    private static final double EXTRA_WIDTH = 2 * UNITS_PER_CHARACTER;

    private static final String WHOLE_COLUMNS = "(?i)[A-Z]{1,3}(:[A-Z]{1,3})?";

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ProcessingConfig processingConfig;

    @Override
    public String getType() {
        return "AUTOSIZE_COLUMNS";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws Exception {
        AutosizeColumnsConfig cfg = mapper.convertValue(properties, AutosizeColumnsConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());

        // Parsed before anything else so a malformed range is reported as one, even on an empty sheet.
        boolean named = cfg.getRange() != null && !cfg.getRange().isBlank();
        CellRangeAddress area = named ? columns(cfg.getRange()) : null;

        int lastUsed = lastUsedColumn(sheet);
        if (lastUsed < 0) {
            return "the sheet is empty, so no column needed sizing";
        }

        int first = area == null ? 0 : Math.min(area.getFirstColumn(), area.getLastColumn());
        // Clamped to real content: "A:XFD" is a plausible way to say "all of them" and must not be
        // charged for 16384 columns of empty sheet.
        int last = Math.min(area == null ? lastUsed : Math.max(area.getFirstColumn(), area.getLastColumn()), lastUsed);
        if (first > last) {
            return "those columns hold nothing, so no width changed";
        }

        int maxWidth = Math.clamp(
                cfg.getMaxWidth() == null ? DEFAULT_MAX_WIDTH : cfg.getMaxWidth(), 1, EXCEL_MAX_WIDTH);
        int ceiling = maxWidth * UNITS_PER_CHARACTER;

        long rows = Math.max(sheet.getPhysicalNumberOfRows(), 1);
        long budget = processingConfig.getMaxAutosizeCells();
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

        int attempted = 0;
        int resized = 0;
        int unchanged = 0;
        int clamped = 0;
        int unmeasurable = 0;
        int overBudget = 0;
        long measured = 0;

        double previousExtra = sheet.getArbitraryExtraWidth();
        sheet.setArbitraryExtraWidth(EXTRA_WIDTH);
        try {
            for (int c = first; c <= last; c++) {
                // Refusing the whole step was worse than useless: the bound is per-step, so a model
                // told "narrow the range" simply issues A:J then K:T and does the identical work.
                // Spending the budget and saying what is left over bounds the time honestly.
                if (measured + rows > budget) {
                    overBudget = last - c + 1;
                    break;
                }
                measured += rows;
                attempted++;

                refreshFormulas(sheet, c, evaluator);

                int before = sheet.getColumnWidth(c);
                try {
                    // Merged regions are excluded on purpose: a merged title spanning A:E would
                    // otherwise stretch column A to the width of the whole title.
                    sheet.autoSizeColumn(c);
                } catch (RuntimeException | LinkageError e) {
                    // A JRE with no fonts fails inside AWT text measurement as a LinkageError
                    // (NoClassDefFoundError / UnsatisfiedLinkError), not an exception, so both have
                    // to be caught to keep one bad column from killing the step.
                    log.warn("Could not measure column {} on '{}': {}", c, sheet.getSheetName(), e.toString());
                    unmeasurable++;
                    continue;
                }
                int after = sheet.getColumnWidth(c);
                if (after > ceiling) {
                    sheet.setColumnWidth(c, ceiling);
                    after = ceiling;
                    clamped++;
                }
                // POI leaves an empty column untouched, so "sized" would otherwise count columns
                // nothing happened to — a false number in a record the user is meant to audit.
                if (after == before) {
                    unchanged++;
                } else {
                    resized++;
                }
            }
        } finally {
            sheet.setArbitraryExtraWidth(previousExtra);
        }

        log.info("AUTOSIZE_COLUMNS on '{}' columns {}-{}: {} resized, {} unchanged, {} clamped,"
                        + " {} unmeasurable, {} over budget",
                sheet.getSheetName(), first, last, resized, unchanged, clamped, unmeasurable, overBudget);

        if (attempted > 0 && unmeasurable == attempted) {
            throw new IllegalStateException("None of the " + attempted + " column(s) could be measured:"
                    + " this JVM cannot measure text (no fonts available), so widths cannot be computed.");
        }
        return detail(resized, unchanged, clamped, unmeasurable, overBudget, maxWidth);
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String label = columnLabel(properties);
        boolean many = label != null && label.startsWith("columns ");
        return ActionDescriptions.verb(tense, "Widen", "Widened") + " "
                + (label == null ? "every column" : label)
                + " to fit " + (many ? "their" : "its") + " contents"
                + ActionDescriptions.sheetSuffix(properties);
    }

    private String detail(int resized, int unchanged, int clamped, int unmeasurable,
                          int overBudget, int maxWidth) {
        StringBuilder text = new StringBuilder();
        if (resized > 0) {
            text.append(resized).append(resized == 1 ? " column resized" : " columns resized");
        } else {
            // "needed no new width" is only true of columns that were actually measured.
            text.append(unmeasurable > 0 || overBudget > 0
                    ? "no column was resized"
                    : "no column needed a new width");
        }
        if (resized > 0 && unchanged > 0) {
            text.append(", ").append(unchanged).append(" already fitted");
        }
        if (clamped > 0) {
            text.append(", ").append(clamped).append(" capped at ").append(maxWidth).append(" characters");
        }
        if (unmeasurable > 0) {
            text.append(", ").append(unmeasurable).append(" left as ")
                    .append(unmeasurable == 1 ? "it was" : "they were").append(" (could not be measured)");
        }
        if (overBudget > 0) {
            text.append(", ").append(overBudget)
                    .append(" not measured (the measurement budget was spent — size them in a"
                            + " further step, or narrow the range)");
        }
        return text.toString();
    }

    /**
     * POI measures a formula cell from its <em>cached</em> result, and the workbook is only
     * recalculated when it is saved — after every action has run. So a plan that adds a total and
     * then sizes the column would size it to the stale 0 the formula was created with, and the saved
     * file would hold the right number in a column too narrow to show it.
     * <p>
     * {@code evaluateFormulaCell} refreshes the cached value and keeps the formula, unlike
     * {@code evaluateInCell} which would replace it with its result.
     */
    private void refreshFormulas(XSSFSheet sheet, int column, FormulaEvaluator evaluator) {
        for (Row row : sheet) {
            Cell cell = row.getCell(column);
            if (cell == null || cell.getCellType() != CellType.FORMULA) {
                continue;
            }
            try {
                evaluator.evaluateFormulaCell(cell);
            } catch (RuntimeException e) {
                // An unsupported function must not cost the whole step; the stale value still measures.
                log.debug("Could not evaluate {} before sizing: {}",
                        new CellAddress(cell).formatAsString(), e.toString());
            }
        }
    }

    /**
     * The columns a range names. Accepts the whole-column form {@code "A:D"} that POI's parser
     * rejects, and refuses a whole-row form, which POI resolves to column -1 rather than failing.
     */
    private CellRangeAddress columns(String raw) {
        String cleaned = raw.substring(raw.lastIndexOf('!') + 1).replace("$", "").trim();
        if (cleaned.matches(WHOLE_COLUMNS)) {
            int colon = cleaned.indexOf(':');
            String firstLetters = colon < 0 ? cleaned : cleaned.substring(0, colon);
            String lastLetters = colon < 0 ? cleaned : cleaned.substring(colon + 1);
            cleaned = firstLetters + "1:" + lastLetters + "1";
        }

        CellRangeAddress area = CellRangeAddress.valueOf(cleaned);
        if (area.getFirstColumn() < 0 || area.getLastColumn() < 0) {
            throw new IllegalArgumentException("\"range\" has to name columns, but \"" + raw
                    + "\" names whole rows — use \"A:D\" or \"A1:D1\".");
        }
        return area;
    }

    /** Rightmost column holding anything, or -1 for an empty sheet. */
    private int lastUsedColumn(XSSFSheet sheet) {
        int last = -1;
        for (Row row : sheet) {
            last = Math.max(last, row.getLastCellNum() - 1);
        }
        return last;
    }

    /** {@code "columns A:D"} — the row parts of a range are noise when only its columns are used. */
    private String columnLabel(Map<String, Object> properties) {
        String range = ActionDescriptions.range(properties, "range");
        if (range == null) {
            return null;
        }
        int colon = range.indexOf(':');
        String first = (colon < 0 ? range : range.substring(0, colon)).replaceAll("\\d", "");
        String last = (colon < 0 ? range : range.substring(colon + 1)).replaceAll("\\d", "");
        if (first.isEmpty() || last.isEmpty()) {
            return range;
        }
        return first.equalsIgnoreCase(last) ? "column " + first : "columns " + first + ":" + last;
    }
}
