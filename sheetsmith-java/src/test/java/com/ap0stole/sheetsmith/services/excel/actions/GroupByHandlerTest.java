package com.ap0stole.sheetsmith.services.excel.actions;

import org.apache.poi.ss.usermodel.FormulaEvaluator;
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
 * The summary block. Every number here is checked by evaluating the formula that was written, not by
 * reading it back as text — the same standard LOOKUP_FROM_SHEET is held to, and the reason a real
 * pivot table was rejected: a pivot's numbers do not exist until Excel itself refreshes them.
 */
class GroupByHandlerTest {

    private XSSFWorkbook workbook;
    private XSSFSheet data;
    private final GroupByHandler groupBy = new GroupByHandler();

    @BeforeEach
    void setUp() {
        workbook = new XSSFWorkbook();
        data = workbook.createSheet("Data");
        XSSFRow header = data.createRow(0);
        header.createCell(0).setCellValue("Region");
        header.createCell(1).setCellValue("Product");
        header.createCell(2).setCellValue("Amount");

        Object[][] rows = {
                {"North", "A", 10.0},
                {"South", "B", 20.0},
                {"North", "B", 5.0},
                {"East", "A", 7.0},
                {"North", "A", 3.0}};
        for (int i = 0; i < rows.length; i++) {
            XSSFRow row = data.createRow(i + 1);
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

    private double evaluated(XSSFSheet sheet, int row, int column) {
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        return evaluator.evaluate(sheet.getRow(row).getCell(column)).getNumberValue();
    }

    @Test
    @DisplayName("one row per distinct key, with the totals resolving to the right numbers")
    void totalsByGroup() throws Exception {
        String detail = groupBy.execute(workbook, props(
                "range", "A1:C6", "groupBy", "A", "valueColumn", "C", "function", "sum",
                "targetSheet", "Summary"));

        XSSFSheet summary = workbook.getSheet("Summary");
        assertThat(summary.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Region");
        assertThat(summary.getRow(0).getCell(1).getStringCellValue()).isEqualTo("Total of Amount");

        assertThat(summary.getRow(1).getCell(0).getStringCellValue()).isEqualTo("North");
        assertThat(evaluated(summary, 1, 1)).isEqualTo(18.0);
        assertThat(summary.getRow(2).getCell(0).getStringCellValue()).isEqualTo("South");
        assertThat(evaluated(summary, 2, 1)).isEqualTo(20.0);
        assertThat(summary.getRow(3).getCell(0).getStringCellValue()).isEqualTo("East");
        assertThat(evaluated(summary, 3, 1)).isEqualTo(7.0);

        assertThat(detail).isEqualTo("3 groups from 5 rows, written to Summary!A1");
    }

    @Test
    @DisplayName("keys keep the order they first appear in, not an alphabetical one")
    void keepsFirstSeenOrder() throws Exception {
        groupBy.execute(workbook, props(
                "range", "A1:C6", "groupBy", "A", "valueColumn", "C", "targetSheet", "Summary"));

        XSSFSheet summary = workbook.getSheet("Summary");
        assertThat(summary.getRow(1).getCell(0).getStringCellValue()).isEqualTo("North");
        assertThat(summary.getRow(3).getCell(0).getStringCellValue()).isEqualTo("East");
    }

    @Test
    @DisplayName("an average is written as a sum over a count, and resolves to the average")
    void averagesWithoutAverageif() throws Exception {
        groupBy.execute(workbook, props(
                "range", "A1:C6", "groupBy", "A", "valueColumn", "C", "function", "average",
                "targetSheet", "Summary"));

        XSSFSheet summary = workbook.getSheet("Summary");
        assertThat(summary.getRow(1).getCell(1).getCellFormula())
                .as("AVERAGEIF is not in POI's function table, so it must not be written")
                .doesNotContain("AVERAGEIF")
                .contains("SUMIF").contains("COUNTIF");
        assertThat(evaluated(summary, 1, 1)).isEqualTo(6.0);
        assertThat(summary.getRow(0).getCell(1).getStringCellValue()).isEqualTo("Average of Amount");
    }

    @Test
    @DisplayName("counting needs no value column")
    void countsRowsPerGroup() throws Exception {
        groupBy.execute(workbook, props(
                "range", "A1:C6", "groupBy", "A", "function", "count", "targetSheet", "Summary"));

        XSSFSheet summary = workbook.getSheet("Summary");
        assertThat(summary.getRow(0).getCell(1).getStringCellValue()).isEqualTo("Count");
        assertThat(evaluated(summary, 1, 1)).isEqualTo(3.0);
        assertThat(evaluated(summary, 3, 1)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a position in the range names the same column its letter would")
    void acceptsColumnPositions() throws Exception {
        groupBy.execute(workbook, props(
                "range", "A1:C6", "groupBy", "1", "valueColumn", "3", "targetSheet", "Summary"));

        assertThat(evaluated(workbook.getSheet("Summary"), 1, 1)).isEqualTo(18.0);
    }

    @Test
    @DisplayName("with no target named, the block lands clear of the data it summarises")
    void writesBesideTheDataByDefault() throws Exception {
        String detail = groupBy.execute(workbook, props(
                "range", "A1:C6", "groupBy", "A", "valueColumn", "C"));

        assertThat(data.getRow(0).getCell(4).getStringCellValue())
                .as("two columns clear of C, so nothing is written over")
                .isEqualTo("Region");
        assertThat(evaluated(data, 1, 5)).isEqualTo(18.0);
        assertThat(detail).contains("written to Data!E1");
    }

    @Test
    @DisplayName("the summary points back at the data sheet by name")
    void qualifiesTheDataRanges() throws Exception {
        groupBy.execute(workbook, props(
                "range", "A1:C6", "groupBy", "A", "valueColumn", "C", "targetSheet", "Summary"));

        assertThat(workbook.getSheet("Summary").getRow(1).getCell(1).getCellFormula())
                .contains("Data!$A$2:$A$6").contains("Data!$C$2:$C$6");
    }

    @Test
    @DisplayName("numeric keys stay numeric, or SUMIF would never match them")
    void keepsNumericKeysNumeric() throws Exception {
        for (int r = 1; r <= 5; r++) {
            data.getRow(r).getCell(0).setCellValue(r <= 3 ? 2026 : 2025);
        }

        groupBy.execute(workbook, props(
                "range", "A1:C6", "groupBy", "A", "valueColumn", "C", "targetSheet", "Summary"));

        XSSFSheet summary = workbook.getSheet("Summary");
        assertThat(summary.getRow(1).getCell(0).getNumericCellValue()).isEqualTo(2026);
        assertThat(evaluated(summary, 1, 1)).isEqualTo(35.0);
    }

    @Test
    @DisplayName("min and max are refused with the reason and the alternatives")
    void refusesMinAndMax() {
        assertThatThrownBy(() -> groupBy.execute(workbook, props(
                "range", "A1:C6", "groupBy", "A", "valueColumn", "C", "function", "max")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sum, average or count");
    }

    @Test
    @DisplayName("a column outside the range is refused")
    void refusesAColumnOutsideTheRange() {
        assertThatThrownBy(() -> groupBy.execute(workbook, props(
                "range", "A1:C6", "groupBy", "Z", "valueColumn", "C")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside");
    }

    @Test
    @DisplayName("a range of only a header row is refused")
    void refusesAHeaderOnlyRange() {
        assertThatThrownBy(() -> groupBy.execute(workbook, props(
                "range", "A1:C1", "groupBy", "A", "valueColumn", "C")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("header row");
    }

    @Test
    @DisplayName("an empty key column is reported rather than writing an empty summary")
    void reportsNothingToGroup() throws Exception {
        for (int r = 1; r <= 5; r++) {
            data.getRow(r).getCell(0).setBlank();
        }

        String detail = groupBy.execute(workbook, props(
                "range", "A1:C6", "groupBy", "A", "valueColumn", "C", "targetSheet", "Summary"));

        assertThat(detail).contains("nothing to group");
        assertThat(workbook.getSheet("Summary")).isNull();
    }
}
