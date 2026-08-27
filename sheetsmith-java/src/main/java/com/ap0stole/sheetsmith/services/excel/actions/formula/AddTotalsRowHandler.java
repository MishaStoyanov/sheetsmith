package com.ap0stole.sheetsmith.services.excel.actions.formula;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.formula.TotalsRowConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.CellStyles;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The row every spreadsheet ends with: a label and a total under each column of numbers.
 * <p>
 * It could be spelled as one ADD_FORMULA per column, and that is exactly what made it worth having —
 * a model asked to "add totals" over an eight-column table emits eight steps, gets one range wrong,
 * and the plan card shows eight lines where the user wanted to approve one idea.
 * <p>
 * The judgement it adds is which columns to total. A SUM over a column of names is {@code 0} — not
 * an error, just a confident lie sitting under the data — so a column is totalled only if it holds
 * numbers, and the ones skipped are named in the result. A column of dates is skipped too: their sum
 * is a number Excel will happily render as a date in the year 6000.
 */
@Slf4j
@Component
public class AddTotalsRowHandler implements ActionHandler {

    private static final String DEFAULT_LABEL = "Total";

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getType() {
        return "ADD_TOTALS_ROW";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws Exception {
        TotalsRowConfig cfg = mapper.convertValue(properties, TotalsRowConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        CellRangeAddress area = CellStyles.area(cfg.getRange(), "range");
        String function = function(cfg.getFunction());

        int totalsRow = area.getLastRow() + 1;
        Row row = sheet.getRow(totalsRow);
        if (row == null) {
            row = sheet.createRow(totalsRow);
        }

        List<String> totalled = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (int c = area.getFirstColumn(); c <= area.getLastColumn(); c++) {
            if (!holdsNumbers(sheet, area, c)) {
                skipped.add(CellReference.convertNumToColString(c));
                continue;
            }
            String column = CellReference.convertNumToColString(c);
            Cell cell = row.getCell(c) == null ? row.createCell(c) : row.getCell(c);
            cell.setCellFormula(function + "(" + column + (area.getFirstRow() + 1)
                    + ":" + column + (area.getLastRow() + 1) + ")");
            emphasise(workbook, sheet, totalsRow, c);
            totalled.add(column);
        }

        if (totalled.isEmpty()) {
            throw new IllegalArgumentException("No column in " + area.formatAsString()
                    + " holds numbers, so there is nothing to total — check the range covers the"
                    + " data rows rather than the headers.");
        }

        // The label goes in the first column only when that column is not itself being totalled,
        // which is the usual shape: a text column of names on the left, numbers to its right.
        String label = cfg.getLabel() == null || cfg.getLabel().isBlank() ? DEFAULT_LABEL : cfg.getLabel();
        boolean labelled = !totalled.contains(CellReference.convertNumToColString(area.getFirstColumn()));
        if (labelled) {
            Cell cell = row.getCell(area.getFirstColumn()) == null
                    ? row.createCell(area.getFirstColumn())
                    : row.getCell(area.getFirstColumn());
            cell.setCellValue(label);
            emphasise(workbook, sheet, totalsRow, area.getFirstColumn());
        }

        log.info("ADD_TOTALS_ROW wrote {} in row {} of '{}': totalled {}, skipped {}",
                function, totalsRow + 1, sheet.getSheetName(), totalled, skipped);

        StringBuilder detail = new StringBuilder("row " + (totalsRow + 1) + " now holds "
                + function + " of " + String.join(", ", totalled));
        if (!skipped.isEmpty()) {
            detail.append("; ").append(String.join(", ", skipped))
                    .append(skipped.size() == 1 ? " was skipped" : " were skipped")
                    .append(" (no numbers to total)");
        }
        if (!labelled) {
            detail.append("; every column held numbers, so the \"").append(label)
                    .append("\" label was left out rather than overwrite a total");
        }
        return detail.toString();
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String range = ActionDescriptions.range(properties, "range");
        String raw = ActionDescriptions.text(properties, "function");
        String function;
        try {
            function = function(raw);
        } catch (RuntimeException e) {
            function = "SUM";
        }
        String reading = switch (function) {
            case "AVERAGE" -> "an average";
            case "COUNT" -> "a count";
            case "MIN" -> "a minimum";
            case "MAX" -> "a maximum";
            default -> "a total";
        };
        return ActionDescriptions.verb(tense, "Add", "Added") + " " + reading + " row under "
                + (range == null ? "the data" : range) + ActionDescriptions.sheetSuffix(properties);
    }

    /**
     * A column worth totalling holds at least one number and no text. One stray label in a column of
     * figures is a header the range picked up by mistake; a column that is mostly words is not a
     * column of figures at all.
     */
    private boolean holdsNumbers(XSSFSheet sheet, CellRangeAddress area, int column) {
        boolean seenNumber = false;
        for (int r = area.getFirstRow(); r <= area.getLastRow(); r++) {
            Row row = sheet.getRow(r);
            Cell cell = row == null ? null : row.getCell(column);
            if (cell == null) {
                continue;
            }
            CellType type = cell.getCellType() == CellType.FORMULA
                    ? cell.getCachedFormulaResultType() : cell.getCellType();
            if (type == CellType.STRING && !cell.getStringCellValue().isBlank()) {
                return false;
            }
            if (type == CellType.NUMERIC) {
                // A date's sum is a number Excel renders as a date centuries away — not a total.
                if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                    return false;
                }
                seenNumber = true;
            }
        }
        return seenNumber;
    }

    /** A totals row reads as a total, not as one more data row. */
    private void emphasise(XSSFWorkbook workbook, XSSFSheet sheet, int row, int column) {
        CellStyles.apply(workbook, sheet, new CellRangeAddress(row, row, column, column),
                new CellStyles.StyleEdit() {
                    @Override
                    public String key(int r, int c) {
                        return "totals-row";
                    }

                    @Override
                    public void apply(XSSFCellStyle style, int r, int c) {
                        XSSFFont current = style.getFont();
                        XSSFFont bold = workbook.createFont();
                        bold.setFontName(current.getFontName());
                        bold.setFontHeightInPoints(current.getFontHeightInPoints());
                        bold.setBold(true);
                        if (current.getXSSFColor() != null) {
                            bold.setColor(current.getXSSFColor());
                        }
                        style.setFont(bold);
                    }
                });
    }

    private String function(String raw) {
        String cleaned = CellStyles.keyword(raw);
        if (cleaned == null) {
            return "SUM";
        }
        return switch (cleaned.toLowerCase(Locale.ROOT)) {
            case "sum", "total" -> "SUM";
            case "average", "avg", "mean" -> "AVERAGE";
            case "count" -> "COUNT";
            case "min", "minimum", "lowest" -> "MIN";
            case "max", "maximum", "highest" -> "MAX";
            default -> throw new IllegalArgumentException("Unknown \"function\" \"" + raw
                    + "\" — use sum, average, count, min or max.");
        };
    }
}
