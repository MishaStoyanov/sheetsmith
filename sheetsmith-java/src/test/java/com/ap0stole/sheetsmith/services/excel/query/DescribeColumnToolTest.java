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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DescribeColumnToolTest {

    private XSSFWorkbook workbook;
    private DescribeColumnTool tool;

    @BeforeEach
    void setUp() {
        workbook = new XSSFWorkbook();
        XSSFSheet sales = workbook.createSheet("Sales");
        // Product | Units | Mixed | Empty
        writeRow(sales, 0, "Product", "Units", "Mixed", "Empty");
        writeRow(sales, 1, "Widget A", 100, 5, null);
        writeRow(sales, 2, "Widget B", 40, "n/a", null);
        writeRow(sales, 3, "Widget A", null, 7, null);
        writeRow(sales, 4, "Widget C", 200, "n/a", null);
        writeRow(sales, 5, "Widget D", 100, 5, null);

        XSSFSheet other = workbook.createSheet("Other");
        writeRow(other, 0, "x", 9);

        tool = new DescribeColumnTool();
    }

    @AfterEach
    void tearDown() throws IOException {
        workbook.close();
    }

    @Test
    void describesANumericColumn() {
        QueryResult result = tool.execute(workbook, props("A2:D6", 1));
        Map<String, Object> data = data(result);

        assertThat(data.get("count")).isEqualTo(4);
        assertThat(data.get("blanks")).isEqualTo(1);
        assertThat(data.get("distinct")).isEqualTo(3);
        assertThat(data.get("type")).isEqualTo("numeric");
        assertThat(data.get("min")).isEqualTo(40L);
        assertThat(data.get("max")).isEqualTo(200L);
        assertThat(samples(data)).containsExactly(100L, 40L, 200L);
        assertThat(result.summary()).contains("Column B").contains("min 40").contains("max 200");
    }

    @Test
    void describesATextColumnWithoutBounds() {
        Map<String, Object> data = data(tool.execute(workbook, props("A2:D6", 0)));

        assertThat(data.get("type")).isEqualTo("text");
        assertThat(data.get("count")).isEqualTo(5);
        assertThat(data.get("distinct")).isEqualTo(4);
        assertThat(data.get("min")).isNull();
        assertThat(data.get("max")).isNull();
    }

    @Test
    void flagsMixedColumns() {
        Map<String, Object> data = data(tool.execute(workbook, props("A2:D6", 2)));

        assertThat(data.get("type")).isEqualTo("mixed");
        assertThat(data.get("min")).isEqualTo(5L);
        assertThat(data.get("max")).isEqualTo(7L);
        assertThat(samples(data)).containsExactly(5L, "n/a", 7L);
    }

    @Test
    void reportsAnEmptyColumn() {
        Map<String, Object> data = data(tool.execute(workbook, props("A2:D6", 3)));

        assertThat(data.get("type")).isEqualTo("empty");
        assertThat(data.get("count")).isEqualTo(0);
        assertThat(data.get("blanks")).isEqualTo(5);
        assertThat(samples(data)).isEmpty();
    }

    @Test
    void samplesAreCappedAtTen() {
        XSSFSheet sales = workbook.getSheet("Sales");
        for (int r = 6; r < 26; r++) {
            writeRow(sales, r, "Product " + r, r, r, null);
        }

        assertThat(samples(data(tool.execute(workbook, props("A2:D26", 0))))).hasSize(10);
    }

    @Test
    void targetsSheetByNameAndByIndex() {
        Map<String, Object> byName = props("A1:B1", 1);
        byName.put("sheetName", "Other");
        Map<String, Object> byIndex = props("A1:B1", 1);
        byIndex.put("sheetIndex", 1);

        assertThat(data(tool.execute(workbook, byName)).get("max")).isEqualTo(9L);
        assertThat(data(tool.execute(workbook, byIndex)).get("max")).isEqualTo(9L);
    }

    @Test
    void requiresAColumnIndex() {
        assertThatThrownBy(() -> tool.execute(workbook, Map.of("range", "A2:D6")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("columnIndex");
    }

    @Test
    void describeIsPlainLanguageAndNeverThrows() {
        assertThat(tool.describe(props("A2:D200", 1))).isEqualTo("Described column B over A2:D200");
        assertThat(tool.describe(Map.of("range", "A2:D200", "columnIndex", 1, "sheetName", "Sales")))
                .isEqualTo("Described column B over A2:D200 on \"Sales\"");
        assertThat(tool.describe(Map.of())).isEqualTo("Described column ?");

        assertThat(tool.describe(props("A2:D200", 1), StepTense.IMPERATIVE))
                .isEqualTo("Describe column B over A2:D200");
        assertThat(tool.describe(Map.of(), StepTense.IMPERATIVE)).isEqualTo("Describe column ?");
    }

    @Test
    void promptSpecStartsWithTheToolName() {
        assertThat(tool.getType()).isEqualTo("DESCRIBE_COLUMN");
        assertThat(tool.promptSpec()).startsWith("DESCRIBE_COLUMN\n   Keys:").contains("columnIndex");
    }

    private static Map<String, Object> props(String range, int columnIndex) {
        Map<String, Object> props = new HashMap<>();
        props.put("range", range);
        props.put("columnIndex", columnIndex);
        return props;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(QueryResult result) {
        return (Map<String, Object>) result.data();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> samples(Map<String, Object> data) {
        return (List<Object>) data.get("sampleValues");
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
