package com.ap0stole.sheetsmith.services.excel.actions.structure;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.structure.SortConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class SortDataHandler implements ActionHandler {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getType() {
        return "SORT_DATA";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws Exception {
        SortConfig cfg = mapper.convertValue(properties, SortConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());

        CellRangeAddress range = CellRangeAddress.valueOf(cfg.getRange());
        int firstRow = range.getFirstRow();
        int lastRow = range.getLastRow();
        int firstCol = range.getFirstColumn();
        int lastCol = range.getLastColumn();
        int sortCol = cfg.getColumnIndex();

        // snapshot row data as 2D array of raw values
        List<Object[]> rowData = new ArrayList<>();
        for (int r = firstRow; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            Object[] cells = new Object[lastCol - firstCol + 1];
            for (int c = firstCol; c <= lastCol; c++) {
                cells[c - firstCol] = row != null ? getCellValue(row, c) : null;
            }
            rowData.add(cells);
        }

        int sortColOffset = sortCol - firstCol;
        rowData.sort((a, b) -> {
            Object va = a[sortColOffset];
            Object vb = b[sortColOffset];
            int cmp = compareValues(va, vb);
            return cfg.isAscending() ? cmp : -cmp;
        });

        // write sorted data back
        for (int r = 0; r < rowData.size(); r++) {
            Row row = sheet.getRow(firstRow + r);
            if (row == null) row = sheet.createRow(firstRow + r);
            Object[] cells = rowData.get(r);
            for (int c = 0; c < cells.length; c++) {
                Cell cell = row.getCell(firstCol + c);
                if (cell == null) cell = row.createCell(firstCol + c);
                setCellValue(cell, cells[c]);
            }
        }

        log.info("Sorted range {} by column {} {}", cfg.getRange(), sortCol,
                cfg.isAscending() ? "ASC" : "DESC");

        return null;
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String range = ActionDescriptions.range(properties, "range");
        String column = ActionDescriptions.column(properties, "columnIndex");
        String sheet = ActionDescriptions.sheetSuffix(properties);
        String verb = ActionDescriptions.verb(tense, "Sort", "Sorted");
        if (range == null && column == null) {
            return verb + " the data" + sheet;
        }

        StringBuilder text = new StringBuilder(verb).append(' ').append(range == null ? "the data" : range);
        if (column != null) {
            text.append(" by ").append(column);
        }
        text.append(ActionDescriptions.flag(properties, "ascending", true) ? ", lowest first" : ", highest first");
        return text.append(sheet).toString();
    }

    private Object getCellValue(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue()
                    : cell.getNumericCellValue();
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> cell.getBooleanCellValue();
            case FORMULA -> cell.getCellFormula();
            default -> null;
        };
    }

    private void setCellValue(Cell cell, Object value) {
        if (value == null) { cell.setBlank(); return; }
        if (value instanceof Double d) cell.setCellValue(d);
        else if (value instanceof Boolean b) cell.setCellValue(b);
        else cell.setCellValue(value.toString());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareValues(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Comparable ca && b instanceof Comparable cb) {
            try { return ca.compareTo(cb); } catch (ClassCastException e) { /* fall through */ }
        }
        return a.toString().compareTo(b.toString());
    }
}
