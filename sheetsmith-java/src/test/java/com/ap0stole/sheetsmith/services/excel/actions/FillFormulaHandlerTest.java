package com.ap0stole.sheetsmith.services.excel.actions;

import com.ap0stole.sheetsmith.services.excel.actions.formula.FillFormulaHandler;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FillFormulaHandlerTest {

    private XSSFWorkbook workbook;
    private XSSFSheet sheet;
    private FillFormulaHandler handler;

    @BeforeEach
    void setUp() {
        workbook = new XSSFWorkbook();
        sheet = workbook.createSheet("Data");
        handler = new FillFormulaHandler();

        // Quantity in B, price in C, a rate in F1 for the absolute-reference case.
        for (int r = 0; r < 5; r++) {
            XSSFRow row = sheet.createRow(r);
            row.createCell(1).setCellValue(r + 1);
            row.createCell(2).setCellValue((r + 1) * 10.0);
        }
        sheet.getRow(0).createCell(5).setCellValue(0.2);
    }

    @AfterEach
    void tearDown() throws IOException {
        workbook.close();
    }

    private Map<String, Object> props(Object... pairs) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            properties.put((String) pairs[i], pairs[i + 1]);
        }
        return properties;
    }

    private String formula(int row, int column) {
        return sheet.getRow(row).getCell(column).getCellFormula();
    }

    // ── Filling down ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("relative references move with the row, which is the whole point")
    void relativeReferencesFollowTheRow() throws Exception {
        String detail = handler.execute(workbook, props("range", "D1:D5", "formula", "B1*C1"));

        assertThat(formula(0, 3)).isEqualTo("B1*C1");
        assertThat(formula(1, 3)).isEqualTo("B2*C2");
        assertThat(formula(4, 3)).isEqualTo("B5*C5");
        assertThat(detail).contains("4 cells filled");
    }

    @Test
    @DisplayName("an absolute reference stays where it was pointed")
    void absoluteReferencesDoNotMove() throws Exception {
        handler.execute(workbook, props("range", "D1:D5", "formula", "C1*$F$1"));

        assertThat(formula(1, 3)).isEqualTo("C2*$F$1");
        assertThat(formula(4, 3)).isEqualTo("C5*$F$1");
    }

    @Test
    @DisplayName("a range inside a function moves as a range, not as text")
    void rangesInsideFunctionsAreShifted() throws Exception {
        handler.execute(workbook, props("range", "D1:D3", "formula", "SUM(B1:C1)"));

        assertThat(formula(2, 3)).isEqualTo("SUM(B3:C3)");
    }

    @Test
    @DisplayName("the top cell's own formula is the source when none is given")
    void theSourceCanAlreadyBeThere() throws Exception {
        sheet.getRow(0).createCell(3).setCellFormula("B1+C1");

        handler.execute(workbook, props("range", "D1:D4"));

        assertThat(formula(3, 3)).isEqualTo("B4+C4");
    }

    @Test
    @DisplayName("a block fills each column downwards from its own top cell")
    void aBlockFillsColumnByColumn() throws Exception {
        sheet.getRow(0).createCell(3).setCellFormula("B1*2");
        sheet.getRow(0).createCell(4).setCellFormula("C1*3");

        handler.execute(workbook, props("range", "D1:E3"));

        assertThat(formula(2, 3)).isEqualTo("B3*2");
        assertThat(formula(2, 4)).isEqualTo("C3*3");
    }

    // ── Filling across ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a one-row range fills sideways, and the columns move instead")
    void aSingleRowFillsAcross() throws Exception {
        sheet.getRow(3).createCell(0).setCellFormula("B1*2");

        handler.execute(workbook, props("range", "A4:C4"));

        assertThat(formula(3, 1)).isEqualTo("C1*2");
        assertThat(formula(3, 2)).isEqualTo("D1*2");
    }

    // ── What it refuses ───────────────────────────────────────────────────────

    @Test
    @DisplayName("one cell is not a fill — ADD_FORMULA is that step, and the error says so")
    void aSingleCellIsRefused() {
        var properties = props("range", "D1:D1", "formula", "B1*C1");
        assertThatThrownBy(() -> handler.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ADD_FORMULA");
    }

    @Test
    @DisplayName("a source cell holding a plain value would fill a constant, so it is refused")
    void aValueSourceIsRefused() {
        var properties = props("range", "B1:B5");
        assertThatThrownBy(() -> handler.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("holds no formula");
    }

    @Test
    @DisplayName("a whole column is refused before it creates a million rows")
    void aWholeColumnIsRefused() {
        var properties = props("range", "D:D", "formula", "B1");
        assertThatThrownBy(() -> handler.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bounded range");
    }

    // ── describe() ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the plan card says what will be filled and that it adjusts")
    void describeReadsAsASentence() {
        assertThat(handler.describe(props("range", "D2:D500", "formula", "B2*C2"), StepTense.IMPERATIVE))
                .isEqualTo("Fill D2:D500 with =B2*C2, adjusted for each row");
        assertThat(handler.describe(props("range", "D2:D500"), StepTense.PAST))
                .isEqualTo("Filled D2:D500, adjusted for each row");
    }

    @Test
    @DisplayName("describe survives the keys a model gets wrong")
    void describeNeverThrows() {
        assertThat(handler.describe(Map.of(), StepTense.PAST)).isNotBlank();
        assertThat(handler.describe(props("range", 4, "formula", true), StepTense.PAST)).isNotBlank();
    }
}
