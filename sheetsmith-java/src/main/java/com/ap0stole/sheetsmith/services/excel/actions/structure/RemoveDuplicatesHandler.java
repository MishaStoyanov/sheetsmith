package com.ap0stole.sheetsmith.services.excel.actions.structure;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.structure.RemoveDuplicatesConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.CellStyles;
import com.ap0stole.sheetsmith.services.excel.CellValues;
import com.ap0stole.sheetsmith.services.excel.FormulaErrorScanner;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.io.IOException;

/**
 * Keeps the first of each repeated row and removes the rest.
 * <p>
 * Two decisions make this safe enough to run over someone's data. It removes <em>whole sheet
 * rows</em> and shifts everything below up — Excel's own Remove Duplicates offers to strip only the
 * selected columns, which silently misaligns every row against the columns beside it, and there is
 * no version of that a user asked for. And the first occurrence is always the survivor, so the
 * result is the input with rows taken out rather than a reordering.
 * <p>
 * What counts as the same row is the <em>displayed</em> value of the compared columns: a formula is
 * compared by what it works out to, a number by its value rather than its formatting, and text
 * case-insensitively, because "ACME" and "Acme" as separate customers is the duplicate a user was
 * asking about. {@code columns} narrows the comparison — duplicate order ids with different
 * timestamps are still duplicate orders.
 */
@Slf4j
@Component
public class RemoveDuplicatesHandler implements ActionHandler {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getType() {
        return "REMOVE_DUPLICATES";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws IOException {
        RemoveDuplicatesConfig cfg = mapper.convertValue(properties, RemoveDuplicatesConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        CellRangeAddress area = CellStyles.area(cfg.getRange(), "range");
        List<Integer> columns = columns(cfg.getColumns(), area);

        // The header is part of the range a model naturally names ("A1:D500"), and comparing it
        // against the data would at best waste a row and at worst delete a repeated heading.
        boolean hasHeader = cfg.getHasHeader() == null || cfg.getHasHeader();
        int firstData = hasHeader ? area.getFirstRow() + 1 : area.getFirstRow();
        int lastData = Math.min(area.getLastRow(), sheet.getLastRowNum());
        if (firstData > lastData) {
            return "there are no data rows in " + area.formatAsString() + ", so nothing was removed";
        }

        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        Map<String, Integer> seen = new HashMap<>();
        List<Integer> duplicates = new ArrayList<>();

        for (int r = firstData; r <= lastData; r++) {
            String key = key(sheet.getRow(r), columns, evaluator);
            Integer first = seen.putIfAbsent(key, r);
            if (first != null) {
                duplicates.add(r);
            }
        }

        if (duplicates.isEmpty()) {
            int rows = lastData - firstData + 1;
            return "no duplicates found — all " + rows + " rows are distinct"
                    + (columns.size() < area.getLastColumn() - area.getFirstColumn() + 1
                    ? " by " + describeColumns(columns) : "");
        }

        List<FormulaErrorScanner.CellError> before = StructureShift.formulaErrors(workbook);

        // Deleted bottom-up in contiguous blocks: every removal renumbers the rows beneath it, so
        // working downwards would delete a different row each time than the one that was found.
        int removed = 0;
        int blockEnd = duplicates.getLast();
        for (int i = duplicates.size() - 1; i >= 0; i--) {
            int row = duplicates.get(i);
            boolean startsBlock = i == 0 || duplicates.get(i - 1) != row - 1;
            if (startsBlock) {
                removed += deleteBlock(sheet, row, blockEnd);
                blockEnd = i == 0 ? row : duplicates.get(i - 1);
            }
        }

        log.info("REMOVE_DUPLICATES removed {} row(s) from {} of '{}', comparing {}",
                removed, area.formatAsString(), sheet.getSheetName(), describeColumns(columns));

        String detail = removed + (removed == 1 ? " duplicate row removed" : " duplicate rows removed")
                + ", keeping the first of each; " + seen.size()
                + (seen.size() == 1 ? " row remains" : " rows remain");
        return detail + StructureShift.brokenFormulas(workbook, before);
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String range = ActionDescriptions.range(properties, "range");
        String columns = ActionDescriptions.text(properties, "columns");

        StringBuilder text = new StringBuilder(ActionDescriptions.verb(tense, "Remove", "Removed"))
                .append(" duplicate rows in ").append(range == null ? "the data" : range);
        if (columns != null && !columns.isBlank()) {
            text.append(", matching on column").append(columns.contains(",") ? "s " : " ")
                    .append(columns.trim().toUpperCase(Locale.ROOT));
        }
        return text.append(ActionDescriptions.sheetSuffix(properties)).toString();
    }

    /** Removes rows {@code first}..{@code last} and pulls everything below them up. */
    private int deleteBlock(XSSFSheet sheet, int first, int last) {
        int lastRow = sheet.getLastRowNum();
        for (int r = first; r <= last; r++) {
            Row row = sheet.getRow(r);
            if (row != null) {
                sheet.removeRow(row);
            }
        }
        int count = last - first + 1;
        if (last < lastRow) {
            sheet.shiftRows(last + 1, lastRow, -count, true, false);
        }
        return count;
    }

    /**
     * What makes two rows the same. Numbers are tidied so {@code 1.0} and {@code 1} match, text is
     * folded to one case, and a null cell is distinct from an empty string only in ways no user
     * means — both read as blank.
     */
    private String key(Row row, List<Integer> columns, FormulaEvaluator evaluator) {
        StringBuilder key = new StringBuilder();
        for (int column : columns) {
            Cell cell = row == null ? null : row.getCell(column);
            Object value = cell == null ? null : CellValues.of(cell, evaluator);
            key.append(value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT))
                    // A separator no cell value can contain, so "ab"+"c" and "a"+"bc" stay distinct.
                    // Written as an escape rather than as the character itself: a raw NUL in a
                    // source file is invisible in every editor and survives a copy-paste as a space.
                    .append('\u0000');
        }
        return key.toString();
    }

    /** The columns to compare — all of the range unless named. */
    private List<Integer> columns(String raw, CellRangeAddress area) {
        List<Integer> columns = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            for (int c = area.getFirstColumn(); c <= area.getLastColumn(); c++) {
                columns.add(c);
            }
            return columns;
        }
        for (String part : raw.split(",")) {
            String letters = part.trim().replaceAll("\\d", "").toUpperCase(Locale.ROOT);
            if (letters.isEmpty() || !letters.matches("[A-Z]{1,3}")) {
                throw new IllegalArgumentException("\"columns\" has to name columns like \"A,C\","
                        + " but was \"" + raw + "\".");
            }
            int index = CellReference.convertColStringToIndex(letters);
            if (index < area.getFirstColumn() || index > area.getLastColumn()) {
                throw new IllegalArgumentException("Column " + letters + " is outside "
                        + area.formatAsString() + ", so it holds nothing to compare.");
            }
            columns.add(index);
        }
        return columns;
    }

    private String describeColumns(List<Integer> columns) {
        return "column" + (columns.size() == 1 ? " " : "s ") + columns.stream()
                .map(CellReference::convertNumToColString).reduce((a, b) -> a + ", " + b).orElse("");
    }
}
