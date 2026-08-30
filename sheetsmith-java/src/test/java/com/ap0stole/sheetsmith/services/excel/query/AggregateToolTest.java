package com.ap0stole.sheetsmith.services.excel.query;

import com.ap0stole.sheetsmith.configs.ChatConfig;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AggregateToolTest {

    private XSSFWorkbook workbook;
    private ChatConfig chatConfig;
    private AggregateTool tool;

    @BeforeEach
    void setUp() {
        workbook = new XSSFWorkbook();
        XSSFSheet sales = workbook.createSheet("Sales");
        writeRow(sales, 0, "Product", "Region", "Units");
        writeRow(sales, 1, "Widget A", "North", 100);
        writeRow(sales, 2, "Widget B", "South", 40);
        writeRow(sales, 3, "Widget A", "North", 60);
        writeRow(sales, 4, "Widget C", "East", 200);
        writeRow(sales, 5, "Widget B", "South", "n/a");

        XSSFSheet other = workbook.createSheet("Other");
        writeRow(other, 0, "x", "y", 7);
        writeRow(other, 1, "x", "y", 3);

        chatConfig = new ChatConfig();
        tool = new AggregateTool(chatConfig);
    }

    @AfterEach
    void tearDown() throws IOException {
        workbook.close();
    }

    @Test
    void sumsSkippingNonNumericCells() {
        assertThat(value("SUM")).isEqualTo(400L);
    }

    @Test
    void averagesOnlyOverNumericCells() {
        assertThat(value("AVG")).isEqualTo(100L);
    }

    @Test
    void computesMinMaxAndMedian() {
        assertThat(value("MIN")).isEqualTo(40L);
        assertThat(value("MAX")).isEqualTo(200L);
        assertThat(value("MEDIAN")).isEqualTo(80L);
    }

    @Test
    void countsNonBlankCellsIncludingText() {
        assertThat(value("COUNT")).isEqualTo(5L);
    }

    @Test
    void countsDistinctStringForms() {
        Map<String, Object> props = props("A2:C6", 0, "COUNT_DISTINCT");
        assertThat(data(tool.execute(workbook, props))).containsEntry("value", 3L);
    }

    @Test
    void groupsAreSortedByValueDescending() {
        Map<String, Object> props = props("A2:C6", 2, "SUM");
        props.put("groupByColumnIndex", 0);

        QueryResult result = tool.execute(workbook, props);
        Map<String, Object> data = data(result);

        assertThat(data).containsEntry("truncated", false);
        assertThat(groups(data)).extracting(g -> g.get("key"))
                .containsExactly("Widget C", "Widget A", "Widget B");
        assertThat(groups(data)).extracting(g -> g.get("value"))
                .containsExactly(200L, 160L, 40L);
        assertThat(result.summary()).isEqualTo("Widget C — 200 (highest of 3 groups)");
    }

    @Test
    void groupsAreCappedAtMaxRows() {
        chatConfig.setMaxRows(2);
        Map<String, Object> props = props("A2:C6", 2, "SUM");
        props.put("groupByColumnIndex", 0);

        Map<String, Object> data = data(tool.execute(workbook, props));

        assertThat(groups(data)).hasSize(2);
        assertThat(data).containsEntry("truncated", true);
    }

    @Test
    void targetsSheetByNameAndByIndex() {
        Map<String, Object> byName = props("A1:C2", 2, "SUM");
        byName.put("sheetName", "Other");
        Map<String, Object> byIndex = props("A1:C2", 2, "SUM");
        byIndex.put("sheetIndex", 1);

        assertThat(data(tool.execute(workbook, byName))).containsEntry("value", 10L);
        assertThat(data(tool.execute(workbook, byIndex))).containsEntry("value", 10L);
    }

    @Test
    void rejectsUnknownOperation() {
        var properties = props("A2:C6", 2, "STDDEV");
        assertThatThrownBy(() -> tool.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("COUNT_DISTINCT");
    }

    @Test
    void rejectsColumnOutsideTheRange() {
        var properties = props("A2:C6", 7, "SUM");
        assertThatThrownBy(() -> tool.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside range A2:C6");
    }

    @Test
    void describeIsPlainLanguageAndNeverThrows() {
        Map<String, Object> props = props("A2:D20", 2, "SUM");
        props.put("groupByColumnIndex", 0);

        assertThat(tool.describe(props)).isEqualTo("Summed column C over A2:D20, grouped by column A");
        assertThat(tool.describe(props("A2:D20", 1, "AVG"))).isEqualTo("Averaged column B over A2:D20");
        assertThat(tool.describe(Map.of())).isEqualTo("Aggregated column ?");

        assertThat(tool.describe(props, StepTense.IMPERATIVE))
                .isEqualTo("Sum column C over A2:D20, grouped by column A");
        assertThat(tool.describe(props("A2:D20", 1, "AVG"), StepTense.IMPERATIVE))
                .isEqualTo("Average column B over A2:D20");
        assertThat(tool.describe(Map.of(), StepTense.IMPERATIVE)).isEqualTo("Aggregate column ?");
    }

    @Test
    void everyOperationHasBothVerbs() {
        assertThat(describeAll(StepTense.IMPERATIVE)).containsExactly(
                "Sum column C over A2:D20",
                "Average column C over A2:D20",
                "Take the minimum of column C over A2:D20",
                "Take the maximum of column C over A2:D20",
                "Count values in column C over A2:D20",
                "Count distinct values in column C over A2:D20",
                "Take the median of column C over A2:D20");
        assertThat(describeAll(StepTense.PAST)).containsExactly(
                "Summed column C over A2:D20",
                "Averaged column C over A2:D20",
                "Took the minimum of column C over A2:D20",
                "Took the maximum of column C over A2:D20",
                "Counted values in column C over A2:D20",
                "Counted distinct values in column C over A2:D20",
                "Took the median of column C over A2:D20");
    }

    private List<String> describeAll(StepTense tense) {
        return Stream.of("SUM", "AVG", "MIN", "MAX", "COUNT", "COUNT_DISTINCT", "MEDIAN")
                .map(operation -> tool.describe(props("A2:D20", 2, operation), tense))
                .toList();
    }

    @Test
    void promptSpecStartsWithTheToolName() {
        assertThat(tool.getType()).isEqualTo("AGGREGATE");
        assertThat(tool.promptSpec()).startsWith("AGGREGATE\n   Keys:").contains("COUNT_DISTINCT");
    }

    private Object value(String operation) {
        return data(tool.execute(workbook, props("A2:C6", 2, operation))).get("value");
    }

    private static Map<String, Object> props(String range, int columnIndex, String operation) {
        Map<String, Object> props = new HashMap<>();
        props.put("range", range);
        props.put("columnIndex", columnIndex);
        props.put("operation", operation);
        return props;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(QueryResult result) {
        return (Map<String, Object>) result.data();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> groups(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("groups");
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
