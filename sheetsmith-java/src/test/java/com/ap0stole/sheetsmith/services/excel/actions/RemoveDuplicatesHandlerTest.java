package com.ap0stole.sheetsmith.services.excel.actions;

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

class RemoveDuplicatesHandlerTest {

    private XSSFWorkbook workbook;
    private XSSFSheet sheet;
    private RemoveDuplicatesHandler handler;

    @BeforeEach
    void setUp() {
        workbook = new XSSFWorkbook();
        sheet = workbook.createSheet("Orders");
        handler = new RemoveDuplicatesHandler();
        rows(new String[][]{
                {"Customer", "Amount"},
                {"Acme", "10"},
                {"Globex", "20"},
                {"Acme", "10"},
                {"Initech", "30"},
        });
    }

    /** Header first; numeric-looking strings are written as numbers so the sheet is realistic. */
    private void rows(String[][] data) {
        for (int r = 0; r < data.length; r++) {
            XSSFRow row = sheet.getRow(r) == null ? sheet.createRow(r) : sheet.getRow(r);
            for (int c = 0; c < data[r].length; c++) {
                String value = data[r][c];
                if (value.matches("-?\\d+(\\.\\d+)?")) {
                    row.createCell(c).setCellValue(Double.parseDouble(value));
                } else {
                    row.createCell(c).setCellValue(value);
                }
            }
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

    private String text(int row, int column) {
        return sheet.getRow(row).getCell(column).getStringCellValue();
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the first of each repeated row survives and the rest are removed")
    void keepsTheFirstOccurrence() throws Exception {
        String detail = handler.execute(workbook, props("range", "A1:B5"));

        assertThat(text(0, 0)).as("the header is not compared against the data").isEqualTo("Customer");
        assertThat(text(1, 0)).isEqualTo("Acme");
        assertThat(text(2, 0)).isEqualTo("Globex");
        assertThat(text(3, 0)).as("the rows below the removal moved up").isEqualTo("Initech");
        assertThat(sheet.getLastRowNum()).isEqualTo(3);
        assertThat(detail).contains("1 duplicate row removed").contains("3 rows remain");
    }

    @Test
    @DisplayName("\"ACME\" and \"Acme\" are the same customer, which is the duplicate people mean")
    void comparisonIsCaseInsensitive() throws Exception {
        sheet.getRow(3).getCell(0).setCellValue("ACME");

        handler.execute(workbook, props("range", "A1:B5"));

        assertThat(sheet.getLastRowNum()).isEqualTo(3);
    }

    @Test
    @DisplayName("\"columns\" narrows what counts as the same row")
    void comparisonCanBeNarrowed() throws Exception {
        sheet.getRow(3).getCell(1).setCellValue(999.0);

        String detail = handler.execute(workbook, props("range", "A1:B5", "columns", "A"));

        assertThat(detail).contains("1 duplicate row removed");
        assertThat(text(3, 0)).isEqualTo("Initech");
    }

    @Test
    @DisplayName("several duplicates in a row are all removed, and the survivors keep their order")
    void handlesConsecutiveDuplicates() throws Exception {
        rows(new String[][]{{}, {}, {}, {"Acme", "10"}, {"Acme", "10"}});
        sheet.createRow(5).createCell(0).setCellValue("Umbrella");
        sheet.getRow(5).createCell(1).setCellValue(40.0);

        handler.execute(workbook, props("range", "A1:B6"));

        assertThat(text(1, 0)).isEqualTo("Acme");
        assertThat(text(2, 0)).isEqualTo("Globex");
        assertThat(text(3, 0)).isEqualTo("Umbrella");
        assertThat(sheet.getLastRowNum()).isEqualTo(3);
    }

    @Test
    @DisplayName("nothing repeated means nothing removed, said plainly")
    void reportsWhenThereIsNothingToDo() throws Exception {
        sheet.getRow(3).getCell(0).setCellValue("Umbrella");

        String detail = handler.execute(workbook, props("range", "A1:B5"));

        assertThat(detail).contains("no duplicates found");
        assertThat(sheet.getLastRowNum()).isEqualTo(4);
    }

    @Test
    @DisplayName("hasHeader false compares the first row too")
    void theHeaderCanBeOptedOutOf() throws Exception {
        sheet.getRow(0).getCell(0).setCellValue("Acme");
        sheet.getRow(0).getCell(1).setCellValue(10.0);

        String detail = handler.execute(workbook, props("range", "A1:B5", "hasHeader", false));

        assertThat(detail).contains("2 duplicate rows removed");
    }

    // ── What it refuses ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a column outside the range holds nothing to compare, so it is refused")
    void aColumnOutsideTheRangeIsRefused() {
        assertThatThrownBy(() -> handler.execute(workbook, props("range", "A1:B5", "columns", "D")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside");
    }

    @Test
    @DisplayName("a whole column is refused before it walks a million rows")
    void aWholeColumnIsRefused() {
        assertThatThrownBy(() -> handler.execute(workbook, props("range", "A:B")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bounded range");
    }

    // ── describe() ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the plan card says what will be compared, because that is the risky part")
    void describeReadsAsASentence() {
        assertThat(handler.describe(props("range", "A1:D500"), StepTense.IMPERATIVE))
                .isEqualTo("Remove duplicate rows in A1:D500");
        assertThat(handler.describe(props("range", "A1:D500", "columns", "a,c"), StepTense.PAST))
                .isEqualTo("Removed duplicate rows in A1:D500, matching on columns A,C");
    }

    @Test
    @DisplayName("describe survives the keys a model gets wrong")
    void describeNeverThrows() {
        assertThat(handler.describe(Map.of(), StepTense.PAST)).isNotBlank();
        assertThat(handler.describe(props("range", 3, "columns", 7), StepTense.PAST)).isNotBlank();
    }
}
