package com.ap0stole.sheetsmith.services.excel;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds the cells that currently evaluate to an Excel error (#DIV/0!, #REF!, …), so a writer can
 * check its own work before claiming success.
 * <p>
 * Strictly read-only: the same in-memory workbook is written to disk after the scan, so nothing
 * here may leave residue — evaluation goes through {@link FormulaEvaluator#evaluate(Cell)}, which
 * returns a value instead of caching one into the cell.
 */
@Slf4j
@Component
public class FormulaErrorScanner {

    /** A single bad fill can break thousands of cells; the model only needs a handful to act on. */
    private static final int MAX_REPORTED = 20;

    /** One broken cell, addressed the way the user sees it in Excel. */
    public record CellError(String sheet, String reference, String error) {

        /** {@code Sales!B4 #DIV/0!} — the form used in traces and in the answer shown to the user. */
        public String label() {
            return sheet + "!" + reference + " " + error;
        }

        /** Identity of the cell itself, so a cell that was already broken is not blamed on the edit. */
        String key() {
            return sheet + "!" + reference;
        }
    }

    public List<CellError> scan(XSSFWorkbook workbook) {
        List<CellError> found = new ArrayList<>();
        if (workbook == null) return found;

        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        evaluator.setIgnoreMissingWorkbooks(true);

        for (int i = 0; i < workbook.getNumberOfSheets() && found.size() < MAX_REPORTED; i++) {
            Sheet sheet = workbook.getSheetAt(i);
            if (sheet == null) continue;
            collect(sheet, evaluator, found);
        }
        return List.copyOf(found);
    }

    /** Errors present now that were not present before — pre-existing ones belong to the user. */
    public static List<CellError> newErrors(List<CellError> before, List<CellError> after) {
        if (after == null || after.isEmpty()) return List.of();
        Set<String> known = new HashSet<>();
        if (before != null) before.forEach(e -> known.add(e.key()));
        return after.stream().filter(e -> !known.contains(e.key())).toList();
    }

    private void collect(Sheet sheet, FormulaEvaluator evaluator, List<CellError> found) {
        for (Row row : sheet) {
            if (row == null) continue;
            for (Cell cell : row) {
                if (found.size() >= MAX_REPORTED) return;
                String error = errorOf(cell, evaluator);
                if (error != null) {
                    found.add(new CellError(sheet.getSheetName(),
                            new CellReference(cell.getRowIndex(), cell.getColumnIndex()).formatAsString(),
                            error));
                }
            }
        }
    }

    /** The error text of a cell, or null when it holds anything else. */
    private String errorOf(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) return null;
        try {
            if (cell.getCellType() == CellType.ERROR) {
                return text(cell.getErrorCellValue());
            }
            if (cell.getCellType() != CellType.FORMULA) {
                return null;
            }
            CellValue value = evaluator.evaluate(cell);
            return value != null && value.getCellType() == CellType.ERROR ? text(value.getErrorValue()) : null;
        } catch (Exception e) {
            // POI cannot evaluate every function Excel has; an unknown one is not a broken cell.
            log.debug("Skipped cell during error scan: {}", e.getMessage());
            return null;
        }
    }

    private String text(int code) {
        try {
            return FormulaError.forInt((byte) code).getString();
        } catch (RuntimeException e) {
            return "#ERROR";
        }
    }
}
