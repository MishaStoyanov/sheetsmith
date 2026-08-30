package com.ap0stole.sheetsmith.services.excel.actions;

import com.ap0stole.sheetsmith.services.excel.actions.formula.AddTotalsRowHandler;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import org.apache.poi.ss.usermodel.CellStyle;
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

class AddTotalsRowHandlerTest {

    private XSSFWorkbook workbook;
    private XSSFSheet sheet;
    private AddTotalsRowHandler handler;

    @BeforeEach
    void setUp() {
        workbook = new XSSFWorkbook();
        sheet = workbook.createSheet("Sales");
        handler = new AddTotalsRowHandler();

        // A names column and two numeric ones — the shape a totals row is usually asked for.
        String[] names = {"Widget", "Gadget", "Sprocket"};
        for (int r = 0; r < 3; r++) {
            XSSFRow row = sheet.createRow(r);
            row.createCell(0).setCellValue(names[r]);
            row.createCell(1).setCellValue((r + 1) * 10.0);
            row.createCell(2).setCellValue((r + 1) * 2.0);
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

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a total lands under each numeric column, labelled in the text one")
    void totalsTheNumericColumns() throws Exception {
        String detail = handler.execute(workbook, props("range", "A1:C3"));

        assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("Total");
        assertThat(sheet.getRow(3).getCell(1).getCellFormula()).isEqualTo("SUM(B1:B3)");
        assertThat(sheet.getRow(3).getCell(2).getCellFormula()).isEqualTo("SUM(C1:C3)");
        assertThat(detail).contains("SUM of B, C").contains("A was skipped");
    }

    @Test
    @DisplayName("a column of words gets no total — its sum would be a confident zero")
    void textColumnsAreSkipped() throws Exception {
        assertThat((Object) sheet.getRow(3)).isNull();

        handler.execute(workbook, props("range", "A1:C3"));

        assertThat(sheet.getRow(3).getCell(0).getCellType())
                .isNotEqualTo(org.apache.poi.ss.usermodel.CellType.FORMULA);
    }

    @Test
    @DisplayName("a column of dates is skipped too — their sum is a date centuries away")
    void dateColumnsAreSkipped() throws Exception {
        CellStyle dated = workbook.createCellStyle();
        dated.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
        for (int r = 0; r < 3; r++) {
            sheet.getRow(r).getCell(1).setCellStyle(dated);
        }

        String detail = handler.execute(workbook, props("range", "A1:C3"));

        assertThat(detail).contains("SUM of C").contains("A, B were skipped");
    }

    @Test
    @DisplayName("the function is chosen by name, and the words users reach for all resolve")
    void theFunctionIsSelectable() throws Exception {
        handler.execute(workbook, props("range", "A1:C3", "function", "average"));

        assertThat(sheet.getRow(3).getCell(1).getCellFormula()).isEqualTo("AVERAGE(B1:B3)");
    }

    @Test
    @DisplayName("the label is the user's if they gave one")
    void theLabelIsSettable() throws Exception {
        handler.execute(workbook, props("range", "A1:C3", "label", "Grand total"));

        assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("Grand total");
    }

    @Test
    @DisplayName("the totals row reads as a total: it comes out bold")
    void theTotalsRowIsEmphasised() throws Exception {
        handler.execute(workbook, props("range", "A1:C3"));

        assertThat(sheet.getRow(3).getCell(1).getCellStyle().getFont().getBold()).isTrue();
    }

    @Test
    @DisplayName("when every column holds numbers the label is left out rather than overwrite a total")
    void theLabelNeverEatsATotal() throws Exception {
        for (int r = 0; r < 3; r++) {
            sheet.getRow(r).getCell(0).setCellValue(r + 1.0);
        }

        String detail = handler.execute(workbook, props("range", "A1:C3"));

        assertThat(sheet.getRow(3).getCell(0).getCellFormula()).isEqualTo("SUM(A1:A3)");
        assertThat(detail).contains("label was left out");
    }

    // ── What it refuses ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a range with nothing to total is an error, not an empty row")
    void nothingToTotalIsRefused() {
        var properties = props("range", "A1:A3");
        assertThatThrownBy(() -> handler.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("holds numbers");
    }

    @Test
    @DisplayName("an unknown function is named rather than quietly summed")
    void anUnknownFunctionIsRefused() {
        var properties = props("range", "A1:C3", "function", "median");
        assertThatThrownBy(() -> handler.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("median");
    }

    // ── describe() ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the plan card names the kind of row being added")
    void describeReadsAsASentence() {
        assertThat(handler.describe(props("range", "A1:C3"), StepTense.IMPERATIVE))
                .isEqualTo("Add a total row under A1:C3");
        assertThat(handler.describe(props("range", "A1:C3", "function", "average"), StepTense.PAST))
                .isEqualTo("Added an average row under A1:C3");
    }

    @Test
    @DisplayName("describe survives the keys a model gets wrong")
    void describeNeverThrows() {
        assertThat(handler.describe(Map.of(), StepTense.PAST)).isNotBlank();
        assertThat(handler.describe(props("range", 1, "function", "median"), StepTense.PAST)).isNotBlank();
    }
}
