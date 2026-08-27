package com.ap0stole.sheetsmith.services.excel.actions;

import com.ap0stole.sheetsmith.services.excel.actions.cell.TransformColumnHandler;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.ap0stole.sheetsmith.services.excel.transform.ColumnTransformRegistry;
import com.ap0stole.sheetsmith.services.excel.transform.DigitsOnlyTransform;
import com.ap0stole.sheetsmith.services.excel.transform.PhoneUsTransform;
import com.ap0stole.sheetsmith.services.excel.transform.ToNumberTransform;
import com.ap0stole.sheetsmith.services.excel.transform.TrimTransform;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The handler over a real workbook. The sheet mirrors the shape that motivated the action: one
 * phone column written seven different ways, one of them stored as a number, and one value the
 * rule cannot rescue.
 */
class TransformColumnHandlerTest {

    private XSSFWorkbook workbook;
    private XSSFSheet sheet;
    private TransformColumnHandler handler;

    @BeforeEach
    void setUp() {
        workbook = new XSSFWorkbook();
        sheet = workbook.createSheet("Worksheet");

        text(0, 2, "Phone");
        text(1, 2, "938-964-6770");
        text(2, 2, "+1 (938) 964-6770");
        number(3, 2, 19389646770d);
        text(4, 2, "555-1234");
        text(5, 2, "938.964.6770");

        handler = new TransformColumnHandler(new ColumnTransformRegistry(java.util.List.of(
                new PhoneUsTransform(), new DigitsOnlyTransform(),
                new TrimTransform(), new ToNumberTransform())));
    }

    @AfterEach
    void tearDown() throws IOException {
        workbook.close();
    }

    @Test
    @DisplayName("every spelling in the column ends up identical, the number-typed cell included")
    void normalisesTheWholeColumn() throws Exception {
        handler.execute(workbook, props("C2:C6", "PHONE_US"));

        assertThat(value(1, 2)).isEqualTo("+1 (938) 964-6770");
        assertThat(value(2, 2)).isEqualTo("+1 (938) 964-6770");
        assertThat(value(3, 2)).isEqualTo("+1 (938) 964-6770");
        assertThat(value(5, 2)).isEqualTo("+1 (938) 964-6770");
        assertThat(value(0, 2)).as("the header is outside the range").isEqualTo("Phone");
    }

    @Test
    @DisplayName("a value the rule cannot convert is kept and counted, never blanked")
    void reportsWhatItCouldNotConvert() throws Exception {
        String detail = handler.execute(workbook, props("C2:C6", "PHONE_US"));

        assertThat(value(4, 2)).isEqualTo("555-1234");
        assertThat(detail).isEqualTo(
                "3 values changed, 1 left as it was (the rule could not convert it)");
    }

    @Test
    @DisplayName("a clean run says only what it changed")
    void detailWithoutSkips() throws Exception {
        String detail = handler.execute(workbook, props("C2:C3", "PHONE_US"));

        assertThat(detail).isEqualTo("1 value changed");
    }

    @Test
    @DisplayName("targetRange writes beside the data and leaves the source column untouched")
    void writesIntoATargetColumn() throws Exception {
        Map<String, Object> props = props("C2:C6", "DIGITS_ONLY");
        props.put("targetRange", "D2:D6");

        handler.execute(workbook, props);

        assertThat(value(1, 2)).as("source is untouched").isEqualTo("938-964-6770");
        assertThat(value(1, 3)).isEqualTo("9389646770");
        assertThat(value(4, 3)).isEqualTo("5551234");
    }

    @Test
    @DisplayName("TO_NUMBER leaves a real number in the cell, not text that looks like one")
    void numericTransformWritesANumber() throws Exception {
        XSSFSheet money = workbook.createSheet("Money");
        money.createRow(0).createCell(0).setCellValue("$1,234.56");

        Map<String, Object> props = props("A1:A1", "TO_NUMBER");
        props.put("sheetName", "Money");
        handler.execute(workbook, props);

        Cell cell = money.getRow(0).getCell(0);
        assertThat(cell.getCellType()).isEqualTo(CellType.NUMERIC);
        assertThat(cell.getNumericCellValue()).isEqualTo(1234.56);
    }

    @Test
    @DisplayName("an empty cell is not a failed conversion")
    void blankCellsAreNotCountedAsSkipped() throws Exception {
        text(6, 2, "");
        String detail = handler.execute(workbook, props("C2:C7", "PHONE_US"));

        assertThat(detail).isEqualTo(
                "3 values changed, 1 left as it was (the rule could not convert it)");
    }

    @Test
    void rejectsARangeSpanningSeveralColumns() {
        assertThatThrownBy(() -> handler.execute(workbook, props("C2:D6", "PHONE_US")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("single column");
    }

    @Test
    @DisplayName("an invented operation names the ones that do exist, so the model can correct itself")
    void rejectsAnUnknownOperation() {
        assertThatThrownBy(() -> handler.execute(workbook, props("C2:C6", "MAKE_IT_NICE")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PHONE_US");
    }

    @Test
    void rejectsAMismatchedTargetRange() {
        Map<String, Object> props = props("C2:C6", "PHONE_US");
        props.put("targetRange", "D2:D3");

        assertThatThrownBy(() -> handler.execute(workbook, props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same number of rows");
    }

    @Test
    @DisplayName("the plan card reads as a sentence, in both tenses")
    void describesItself() {
        Map<String, Object> props = props("C2:C35001", "PHONE_US");

        assertThat(handler.describe(props, StepTense.IMPERATIVE))
                .isEqualTo("Rewrite C2:C35001 as +1 (XXX) XXX-XXXX phone numbers");
        assertThat(handler.describe(props, StepTense.PAST))
                .startsWith("Rewrote C2:C35001");
    }

    @Test
    @DisplayName("describe never throws on the half-filled property maps a model sends")
    void describesIncompleteProperties() {
        assertThat(handler.describe(Map.of(), StepTense.IMPERATIVE)).isEqualTo("Rewrite the column");
        assertThat(handler.describe(Map.of("range", "C2:C6"), StepTense.IMPERATIVE))
                .isEqualTo("Rewrite C2:C6");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> props(String range, String operation) {
        Map<String, Object> props = new HashMap<>();
        props.put("range", range);
        props.put("operation", operation);
        return props;
    }

    private void text(int row, int col, String value) {
        cell(row, col).setCellValue(value);
    }

    private void number(int row, int col, double value) {
        cell(row, col).setCellValue(value);
    }

    private Cell cell(int rowIdx, int colIdx) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) row = sheet.createRow(rowIdx);
        Cell cell = row.getCell(colIdx);
        return cell == null ? row.createCell(colIdx) : cell;
    }

    private String value(int rowIdx, int colIdx) {
        Cell cell = sheet.getRow(rowIdx).getCell(colIdx);
        return cell.getCellType() == CellType.NUMERIC
                ? String.valueOf((long) cell.getNumericCellValue())
                : cell.getStringCellValue();
    }
}
