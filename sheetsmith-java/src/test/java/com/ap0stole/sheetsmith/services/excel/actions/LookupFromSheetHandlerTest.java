package com.ap0stole.sheetsmith.services.excel.actions;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.xssf.usermodel.XSSFCell;
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

/**
 * A column pulled across from another sheet. The assertions that matter here <em>evaluate</em> the
 * formulas rather than reading them back as text: a formula POI can parse is not the same thing as
 * a formula Excel will resolve, and the whole point of building the fallback out of IF and ISNA
 * instead of IFNA is that POI knows those and can prove it.
 */
class LookupFromSheetHandlerTest {

    private XSSFWorkbook workbook;
    private XSSFSheet orders;
    private final LookupFromSheetHandler lookup = new LookupFromSheetHandler();

    @BeforeEach
    void setUp() {
        workbook = new XSSFWorkbook();

        orders = workbook.createSheet("Orders");
        orders.createRow(0).createCell(0).setCellValue("SKU");
        orders.getRow(0).createCell(1).setCellValue("Price");
        String[] skus = {"A-1", "B-2", "C-3"};
        for (int i = 0; i < skus.length; i++) {
            orders.createRow(i + 1).createCell(0).setCellValue(skus[i]);
        }

        XSSFSheet products = workbook.createSheet("Products");
        products.createRow(0).createCell(0).setCellValue("SKU");
        products.getRow(0).createCell(1).setCellValue("Name");
        products.getRow(0).createCell(2).setCellValue("Price");
        Object[][] rows = {{"A-1", "Widget", 10.5}, {"B-2", "Gadget", 20.0}};
        for (int i = 0; i < rows.length; i++) {
            XSSFRow row = products.createRow(i + 1);
            row.createCell(0).setCellValue((String) rows[i][0]);
            row.createCell(1).setCellValue((String) rows[i][1]);
            row.createCell(2).setCellValue((Double) rows[i][2]);
        }
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

    private CellValue evaluated(int row, int column) {
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        return evaluator.evaluate(orders.getRow(row).getCell(column));
    }

    private XSSFCell cell(int row, int column) {
        return orders.getRow(row).getCell(column);
    }

    @Test
    @DisplayName("the looked-up values resolve to what the other sheet holds")
    void bringsTheValuesAcross() throws Exception {
        String detail = lookup.execute(workbook, props(
                "range", "B2:B4", "keyRange", "A2:A4",
                "sourceRange", "Products!A2:C3", "sourceColumn", "3"));

        assertThat(evaluated(1, 1).getNumberValue()).isEqualTo(10.5);
        assertThat(evaluated(2, 1).getNumberValue()).isEqualTo(20.0);
        assertThat(detail).contains("1 of 3 keys has no match").contains("Products");
    }

    @Test
    @DisplayName("a key with no match blanks its row rather than filling the column with errors")
    void blanksTheMisses() throws Exception {
        lookup.execute(workbook, props(
                "range", "B2:B4", "keyRange", "A2:A4",
                "sourceRange", "Products!A2:C3", "sourceColumn", "3"));

        CellValue missed = evaluated(3, 1);
        assertThat(missed.getCellType())
                .as("a POI evaluation proves IF/ISNA is a real function here, not an unknown name")
                .isEqualTo(CellType.STRING);
        assertThat(missed.getStringValue()).isEmpty();
    }

    @Test
    @DisplayName("a column letter names the same column its position would")
    void acceptsAColumnLetter() throws Exception {
        lookup.execute(workbook, props(
                "range", "B2:B4", "keyRange", "A2:A4",
                "sourceRange", "Products!A2:C3", "sourceColumn", "C"));

        assertThat(evaluated(1, 1).getNumberValue()).isEqualTo(10.5);
    }

    @Test
    @DisplayName("the table reference is absolute, so every row looks at the same block")
    void anchorsTheSourceTable() throws Exception {
        lookup.execute(workbook, props(
                "range", "B2:B4", "keyRange", "A2:A4",
                "sourceRange", "Products!A2:C3", "sourceColumn", "3"));

        assertThat(cell(1, 1).getCellFormula())
                .contains("Products!$A$2:$C$3")
                .contains("$A2")
                .as("approximate matching returns the nearest smaller key and looks like it worked")
                .contains("FALSE");
        assertThat(cell(2, 1).getCellFormula()).contains("$A3");
    }

    @Test
    @DisplayName("a chosen fallback shows instead of a blank")
    void usesTheGivenFallback() throws Exception {
        lookup.execute(workbook, props(
                "range", "B2:B4", "keyRange", "A2:A4", "sourceRange", "Products!A2:C3",
                "sourceColumn", "3", "ifMissing", "not found"));

        assertThat(evaluated(3, 1).getStringValue()).isEqualTo("not found");
    }

    @Test
    @DisplayName("asking for #N/A leaves the error in place, and the step says so")
    void leavesTheErrorWhenAsked() throws Exception {
        String detail = lookup.execute(workbook, props(
                "range", "B2:B4", "keyRange", "A2:A4", "sourceRange", "Products!A2:C3",
                "sourceColumn", "3", "ifMissing", "#N/A"));

        assertThat(cell(1, 1).getCellFormula()).startsWith("VLOOKUP(");
        assertThat(detail).contains("will show #N/A");
    }

    @Test
    @DisplayName("a sheet named separately works as well as a prefixed range")
    void acceptsASeparateSourceSheet() throws Exception {
        lookup.execute(workbook, props(
                "range", "B2:B4", "keyRange", "A2:A4", "sourceRange", "A2:C3",
                "sourceSheet", "Products", "sourceColumn", "3"));

        assertThat(evaluated(1, 1).getNumberValue()).isEqualTo(10.5);
    }

    @Test
    @DisplayName("a sheet name with a space is quoted the way Excel needs")
    void quotesASheetNameWithASpace() throws Exception {
        workbook.setSheetName(workbook.getSheetIndex("Products"), "Price list");

        lookup.execute(workbook, props(
                "range", "B2:B4", "keyRange", "A2:A4", "sourceRange", "'Price list'!A2:C3",
                "sourceColumn", "3"));

        assertThat(cell(1, 1).getCellFormula()).contains("'Price list'!$A$2:$C$3");
        assertThat(evaluated(1, 1).getNumberValue()).isEqualTo(10.5);
    }

    @Test
    @DisplayName("mismatched heights are refused with both counts named")
    void refusesMismatchedHeights() {
        assertThatThrownBy(() -> lookup.execute(workbook, props(
                "range", "B2:B4", "keyRange", "A2:A10",
                "sourceRange", "Products!A2:C3", "sourceColumn", "3")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same rows");
    }

    @Test
    @DisplayName("more than one target column is refused — a lookup writes one")
    void refusesAWideTarget() {
        assertThatThrownBy(() -> lookup.execute(workbook, props(
                "range", "B2:C4", "keyRange", "A2:A4",
                "sourceRange", "Products!A2:C3", "sourceColumn", "3")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one column");
    }

    @Test
    @DisplayName("a column outside the source range is refused before it becomes #REF")
    void refusesAColumnOutsideTheSource() {
        assertThatThrownBy(() -> lookup.execute(workbook, props(
                "range", "B2:B4", "keyRange", "A2:A4",
                "sourceRange", "Products!A2:C3", "sourceColumn", "9")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside");
    }

    @Test
    @DisplayName("a source sheet that does not exist names how to point at one")
    void refusesAMissingSourceSheet() {
        assertThatThrownBy(() -> lookup.execute(workbook, props(
                "range", "B2:B4", "keyRange", "A2:A4",
                "sourceRange", "Catalogue!A2:C3", "sourceColumn", "3")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Catalogue");
    }

    @Test
    @DisplayName("a number key does not match a key stored as text, and the count says so")
    void keepsNumbersAndTextApart() throws Exception {
        orders.getRow(1).getCell(0).setCellValue(42);
        XSSFSheet products = workbook.getSheet("Products");
        products.getRow(1).getCell(0).setCellValue("42");

        String detail = lookup.execute(workbook, props(
                "range", "B2:B4", "keyRange", "A2:A4",
                "sourceRange", "Products!A2:C3", "sourceColumn", "3"));

        assertThat(detail)
                .as("Excel does not match the number 42 to the text \"42\", so neither does the count")
                .contains("2 of 3 keys have no match");
    }
}
