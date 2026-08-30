package com.ap0stole.sheetsmith.services.excel.query;

import com.ap0stole.sheetsmith.services.excel.StepTense;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvalFormulaToolTest {

    private XSSFWorkbook workbook;
    private EvalFormulaTool tool;

    @BeforeEach
    void setUp() {
        workbook = new XSSFWorkbook();
        XSSFSheet sales = workbook.createSheet("Sales");
        writeRow(sales, 0, "Product", "Units", "Revenue");
        writeRow(sales, 1, "Widget A", 100, 500);
        writeRow(sales, 2, "Widget B", 40, 300);
        writeRow(sales, 3, "Widget C", 200, 250);
        writeRow(sales, 4, "Widget D", 30, 0);

        XSSFSheet other = workbook.createSheet("Other");
        writeRow(other, 0, "x", 1, 1);
        writeRow(other, 1, "y", 2, 2);

        tool = new EvalFormulaTool();
    }

    @AfterEach
    void tearDown() throws IOException {
        workbook.close();
    }

    @Test
    void evaluatesAggregateFormulas() {
        Map<String, Object> data = data(tool.execute(workbook, Map.of("formula", "SUM(C2:C5)")));

        assertThat(data)
                .containsEntry("formula", "SUM(C2:C5)")
                .containsEntry("value", 1050L)
                .containsEntry("type", "number");
    }

    @Test
    void evaluatesConditionalFormulas() {
        QueryResult result = tool.execute(workbook, Map.of("formula", "SUMIF(B2:B5,\">50\",C2:C5)"));

        assertThat(data(result)).containsEntry("value", 750L);
        assertThat(result.summary()).isEqualTo("=SUMIF(B2:B5,\">50\",C2:C5) = 750");
    }

    @Test
    void evaluatesLookupsTextAndBooleans() {
        assertThat(data(tool.execute(workbook, Map.of("formula", "VLOOKUP(\"Widget C\",A2:C5,3,FALSE)")))).containsEntry("value", 250L);

        Map<String, Object> text = data(tool.execute(workbook, Map.of("formula", "UPPER(A2)")));
        assertThat(text)
                .containsEntry("value", "WIDGET A")
                .containsEntry("type", "text");

        Map<String, Object> bool = data(tool.execute(workbook, Map.of("formula", "COUNT(B2:B5)>2")));
        assertThat(bool)
                .containsEntry("value", true)
                .containsEntry("type", "boolean");
    }

    @Test
    void stripsALeadingEqualsSign() {
        assertThat(data(tool.execute(workbook, Map.of("formula", "=MAX(B2:B5)")))).containsEntry("value", 200L);
    }

    @Test
    void reportsExcelErrorsAsValues() {
        Map<String, Object> data = data(tool.execute(workbook, Map.of("formula", "1/0")));

        assertThat(data)
                .containsEntry("type", "error")
                .containsEntry("value", "#DIV/0!");
    }

    @Test
    void relativeReferencesResolveAgainstTheTargetSheet() {
        Object first = data(tool.execute(workbook, Map.of("formula", "SUM(B1:B2)", "sheetName", "Other"))).get("value");
        Object second = data(tool.execute(workbook, Map.of("formula", "SUM(B1:B2)", "sheetIndex", 1))).get("value");

        assertThat(first).isEqualTo(3L);
        assertThat(second).isEqualTo(3L);
        // same reference on the default sheet reads a different column of data
        assertThat(data(tool.execute(workbook, Map.of("formula", "SUM(B1:B2)")))).containsEntry("value", 100L);
    }

    @Test
    void leavesTheWorkbookUntouched() {
        XSSFSheet sales = workbook.getSheet("Sales");
        int lastRowBefore = sales.getLastRowNum();
        int physicalRowsBefore = sales.getPhysicalNumberOfRows();
        String xmlBefore = sales.getCTWorksheet().toString();

        tool.execute(workbook, Map.of("formula", "SUMIF(B2:B5,\">50\",C2:C5)"));

        assertThat(sales.getLastRowNum()).isEqualTo(lastRowBefore);
        assertThat(sales.getPhysicalNumberOfRows()).isEqualTo(physicalRowsBefore);
        assertThat((Object) sales.getRow(lastRowBefore + 5)).isNull();
        assertThat(sales.getCTWorksheet()).hasToString(xmlBefore);
    }

    @Test
    void cleansUpEvenWhenTheFormulaIsRejected() {
        XSSFSheet sales = workbook.getSheet("Sales");
        int lastRowBefore = sales.getLastRowNum();

        Map<String, Object> arguments = Map.of("formula", "SUM(");
        assertThatThrownBy(() -> tool.execute(workbook, arguments))
                .isInstanceOf(RuntimeException.class);

        assertThat(sales.getLastRowNum()).isEqualTo(lastRowBefore);
        assertThat((Object) sales.getRow(lastRowBefore + 5)).isNull();
    }

    @Test
    void repeatedEvaluationStaysConsistent() {
        for (int i = 0; i < 3; i++) {
            assertThat(data(tool.execute(workbook, Map.of("formula", "SUM(C2:C5)")))).containsEntry("value", 1050L);
        }
        assertThat(workbook.getSheet("Sales").getLastRowNum()).isEqualTo(4);
    }

    @Test
    void requiresAFormula() {
        Map<String, Object> arguments2 = Map.of("formula", " ");
        assertThatThrownBy(() -> tool.execute(workbook, arguments2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("formula");
    }

    @Test
    void describeIsPlainLanguageAndNeverThrows() {
        assertThat(tool.describe(Map.of("formula", "SUMIF(B2:B20,\">100\",C2:C20)")))
                .isEqualTo("Evaluated =SUMIF(B2:B20,\">100\",C2:C20)");
        assertThat(tool.describe(Map.of("formula", "=MAX(B:B)", "sheetName", "Sales")))
                .isEqualTo("Evaluated =MAX(B:B) on \"Sales\"");
        assertThat(tool.describe(Map.of())).isEqualTo("Evaluated a formula");

        assertThat(tool.describe(Map.of("formula", "=MAX(B:B)", "sheetName", "Sales"), StepTense.IMPERATIVE))
                .isEqualTo("Evaluate =MAX(B:B) on \"Sales\"");
        assertThat(tool.describe(Map.of(), StepTense.IMPERATIVE)).isEqualTo("Evaluate a formula");
    }

    @Test
    void promptSpecStartsWithTheToolName() {
        assertThat(tool.getType()).isEqualTo("EVAL_FORMULA");
        assertThat(tool.promptSpec()).startsWith("EVAL_FORMULA\n   Keys:").contains("SUMIF");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(QueryResult result) {
        return (Map<String, Object>) result.data();
    }

    private static void writeRow(XSSFSheet sheet, int rowIdx, Object... values) {
        Row row = sheet.createRow(rowIdx);
        for (int c = 0; c < values.length; c++) {
            Object v = values[c];
            if (v == null) continue;
            Cell cell = row.createCell(c);
            if (v instanceof Number n) cell.setCellValue(n.doubleValue());
            else if (v instanceof Boolean b) cell.setCellValue(b);
            else cell.setCellValue(String.valueOf(v));
        }
    }
}
