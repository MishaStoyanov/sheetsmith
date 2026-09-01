package com.ap0stole.sheetsmith.services.excel.actions.formula;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.formula.FillFormulaConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.CellStyles;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.formula.FormulaParser;
import org.apache.poi.ss.formula.FormulaRenderer;
import org.apache.poi.ss.formula.FormulaShifter;
import org.apache.poi.ss.formula.FormulaType;
import org.apache.poi.ss.formula.ptg.Ptg;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFEvaluationWorkbook;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.io.IOException;

/**
 * Excel's fill handle: one formula down a column, with its references moving as it goes.
 * <p>
 * ADD_FORMULA writes a single cell, which made "compute the margin for every row" a request the
 * engine could only answer by writing five hundred separate steps. The distinction that makes this
 * work is <em>relative</em> references: {@code =B2*C2} filled from row 2 to row 3 has to become
 * {@code =B3*C3}, while {@code =B2*$F$1} has to keep its {@code $F$1}. That is not string
 * substitution — it is what POI's own {@link FormulaShifter} does when Excel copies a cell, so the
 * formula is parsed to tokens, shifted, and rendered back rather than edited as text.
 * <p>
 * The top cell of the range is the source: either it already holds the formula, or {@code formula}
 * supplies one and it is written there first. A block fills downwards column by column, each column
 * taking its own top cell as the source, which is what Excel does for a block and Ctrl+D.
 */
@Slf4j
@Component
public class FillFormulaHandler implements ActionHandler {

    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Override
    public String getType() {
        return "FILL_FORMULA";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws IOException {
        FillFormulaConfig cfg = mapper.convertValue(properties, FillFormulaConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        CellRangeAddress area = CellStyles.area(cfg.getRange(), "range");

        boolean across = area.getFirstRow() == area.getLastRow()
                && area.getFirstColumn() != area.getLastColumn();
        int filled = across
                ? fillAcross(workbook, sheet, area, cfg.getFormula())
                : fillDown(workbook, sheet, area, cfg.getFormula());

        if (filled == 0) {
            // One cell is a range whose source is its own target: ADD_FORMULA is that step, and
            // silently doing nothing here would look like the fill worked.
            throw new IllegalArgumentException(area.formatAsString() + " is a single cell, so there"
                    + " is nothing to fill into — name the whole range to fill (e.g. \"D2:D500\"),"
                    + " or use ADD_FORMULA for one cell.");
        }

        log.info("FILL_FORMULA filled {} cell(s) {} in {} on '{}'",
                filled, across ? "across" : "down", area.formatAsString(), sheet.getSheetName());
        return filled + (filled == 1 ? " cell filled" : " cells filled")
                + ", each referring to its own row";
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String range = ActionDescriptions.range(properties, "range");
        String formula = ActionDescriptions.formula(properties, "formula");

        StringBuilder text = new StringBuilder(ActionDescriptions.verb(tense, "Fill", "Filled"))
                .append(' ').append(range == null ? "the range" : range);
        if (formula != null) {
            text.append(" with ").append(formula);
        }
        return text.append(", adjusted for each row")
                .append(ActionDescriptions.sheetSuffix(properties)).toString();
    }

    /** Each column of the range takes its own top cell as the source — Excel's Ctrl+D over a block. */
    private int fillDown(XSSFWorkbook workbook, XSSFSheet sheet, CellRangeAddress area, String formula) {
        int filled = 0;
        for (int c = area.getFirstColumn(); c <= area.getLastColumn(); c++) {
            String source = source(sheet, area.getFirstRow(), c, formula, area);
            for (int r = area.getFirstRow() + 1; r <= area.getLastRow(); r++) {
                write(sheet, r, c, shifted(workbook, sheet, source,
                        area.getFirstRow(), c, r - area.getFirstRow(), 0));
                filled++;
            }
        }
        return filled;
    }

    private int fillAcross(XSSFWorkbook workbook, XSSFSheet sheet, CellRangeAddress area, String formula) {
        int row = area.getFirstRow();
        String source = source(sheet, row, area.getFirstColumn(), formula, area);
        int filled = 0;
        for (int c = area.getFirstColumn() + 1; c <= area.getLastColumn(); c++) {
            write(sheet, row, c, shifted(workbook, sheet, source,
                    row, area.getFirstColumn(), 0, c - area.getFirstColumn()));
            filled++;
        }
        return filled;
    }

    /**
     * The formula to fill from, written into the source cell when it was supplied rather than found.
     * A source cell holding a plain value is a mistake worth naming: filling it would copy a
     * constant into five hundred rows and report success.
     */
    private String source(XSSFSheet sheet, int row, int column, String formula, CellRangeAddress area) {
        if (formula != null && !formula.isBlank()) {
            String cleaned = formula.replaceAll("^=+", "").trim();
            cellAt(sheet, row, column).setCellFormula(cleaned);
            return cleaned;
        }
        Cell cell = sheet.getRow(row) == null ? null : sheet.getRow(row).getCell(column);
        if (cell == null || cell.getCellType() != CellType.FORMULA) {
            throw new IllegalArgumentException("The top cell of " + area.formatAsString()
                    + " holds no formula to fill from — give \"formula\", e.g. \"B2*C2\".");
        }
        return cell.getCellFormula();
    }

    /**
     * The source formula as it reads {@code deltaRows} down and {@code deltaColumns} across.
     * <p>
     * Parsed, shifted and rendered rather than rewritten as text, because only the parser knows that
     * {@code $F$1} must not move, that {@code SUM(B2:B4)} has two references in it, and that the
     * {@code B2} inside {@code "B2 sold most"} is a string.
     */
    private String shifted(XSSFWorkbook workbook, XSSFSheet sheet, String formula,
                           int sourceRow, int sourceColumn, int deltaRows, int deltaColumns) {
        if (deltaRows == 0 && deltaColumns == 0) {
            return formula;
        }
        XSSFEvaluationWorkbook evaluation = XSSFEvaluationWorkbook.create(workbook);
        int sheetIndex = workbook.getSheetIndex(sheet);
        Ptg[] tokens = FormulaParser.parse(formula, evaluation, FormulaType.CELL, sheetIndex, sourceRow);

        FormulaShifter shifter = deltaRows != 0
                ? FormulaShifter.createForRowCopy(sheetIndex, sheet.getSheetName(),
                sourceRow, sourceRow, deltaRows, SpreadsheetVersion.EXCEL2007)
                : FormulaShifter.createForColumnCopy(sheetIndex, sheet.getSheetName(),
                sourceColumn, sourceColumn, deltaColumns, SpreadsheetVersion.EXCEL2007);
        shifter.adjustFormula(tokens, sheetIndex);

        return FormulaRenderer.toFormulaString(evaluation, tokens);
    }

    /**
     * A formula that shifted off the sheet renders as {@code #REF!}, which POI will not parse back
     * as a formula. Writing it as an error value is what Excel shows in the same situation.
     */
    private void write(XSSFSheet sheet, int row, int column, String formula) {
        Cell cell = cellAt(sheet, row, column);
        try {
            cell.setCellFormula(formula);
        } catch (RuntimeException e) {
            log.debug("Filled formula \"{}\" is not valid at row {}: {}", formula, row + 1, e.toString());
            cell.setCellErrorValue(org.apache.poi.ss.usermodel.FormulaError.REF.getCode());
        }
    }

    private Cell cellAt(XSSFSheet sheet, int rowIdx, int colIdx) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) {
            row = sheet.createRow(rowIdx);
        }
        Cell cell = row.getCell(colIdx);
        return cell == null ? row.createCell(colIdx) : cell;
    }
}
