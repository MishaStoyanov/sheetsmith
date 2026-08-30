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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FindRowsToolTest {

    private XSSFWorkbook workbook;
    private ChatConfig chatConfig;
    private FindRowsTool tool;

    @BeforeEach
    void setUp() {
        workbook = new XSSFWorkbook();
        XSSFSheet sales = workbook.createSheet("Sales");
        writeRow(sales, 0, "Product", "Region", "Units");
        writeRow(sales, 1, "Widget A", "North", 100);
        writeRow(sales, 2, "Widget B", "South", 40);
        writeRow(sales, 3, "Widget A", "North", 60);
        writeRow(sales, 4, "Widget C", "East", 200);
        writeRow(sales, 5, "Widget D", "West", 30);

        XSSFSheet other = workbook.createSheet("Other");
        writeRow(other, 0, "only", "row", 1);

        chatConfig = new ChatConfig();
        tool = new FindRowsTool(chatConfig);
    }

    @AfterEach
    void tearDown() throws IOException {
        workbook.close();
    }

    @Test
    void returnsEveryRowWhenThereAreNoFilters() {
        Map<String, Object> data = data(tool.execute(workbook, props("A2:C6")));

        assertThat(data)
                .containsEntry("matched", 5)
                .containsEntry("returned", 5)
                .containsEntry("truncated", false);
        assertThat(rows(data).getFirst()).containsExactly("Widget A", "North", 100L);
    }

    @Test
    void filtersNumericallyWhenBothSidesAreNumbers() {
        Map<String, Object> props = props("A2:C6");
        props.put("filters", List.of(Map.of("columnIndex", 2, "operator", ">", "value", "50")));

        Map<String, Object> data = data(tool.execute(workbook, props));

        assertThat(data).containsEntry("matched", 3);
        assertThat(rows(data)).extracting(r -> r.get(0))
                .containsExactly("Widget A", "Widget A", "Widget C");
    }

    @Test
    void containsIsCaseInsensitive() {
        Map<String, Object> props = props("A2:C6");
        props.put("filters", List.of(Map.of("columnIndex", 0, "operator", "contains", "value", "widget c")));

        QueryResult result = tool.execute(workbook, props);

        assertThat(data(result)).containsEntry("matched", 1);
        assertThat(result.summary()).isEqualTo("1 row matched");
    }

    @Test
    void combinesFiltersWithAnd() {
        Map<String, Object> props = props("A2:C6");
        props.put("filters", List.of(
                Map.of("columnIndex", 1, "operator", "=", "value", "north"),
                Map.of("columnIndex", 2, "operator", ">=", "value", "100")));

        assertThat(data(tool.execute(workbook, props))).containsEntry("matched", 1);
    }

    @Test
    void sortsDescendingAndTruncatesToTheLimit() {
        Map<String, Object> props = props("A2:C6");
        props.put("sortColumnIndex", 2);
        props.put("ascending", false);
        props.put("limit", 2);

        QueryResult result = tool.execute(workbook, props);
        Map<String, Object> data = data(result);

        assertThat(rows(data)).extracting(r -> r.get(0)).containsExactly("Widget C", "Widget A");
        assertThat(data)
                .containsEntry("matched", 5)
                .containsEntry("returned", 2)
                .containsEntry("truncated", true);
        assertThat(result.summary()).isEqualTo("5 rows matched, returned the first 2");
    }

    @Test
    void sortsAscendingByDefault() {
        Map<String, Object> props = props("A2:C6");
        props.put("sortColumnIndex", 2);

        assertThat(rows(data(tool.execute(workbook, props))))
                .extracting(r -> r.get(2))
                .containsExactly(30L, 40L, 60L, 100L, 200L);
    }

    @Test
    void projectsOnlyTheRequestedColumns() {
        Map<String, Object> props = props("A2:C6");
        props.put("columns", List.of(0, 2));

        assertThat(rows(data(tool.execute(workbook, props))).getFirst())
                .containsExactly("Widget A", 100L);
    }

    @Test
    void limitIsClampedToMaxRows() {
        chatConfig.setMaxRows(2);
        Map<String, Object> props = props("A2:C6");
        props.put("limit", 50);

        Map<String, Object> data = data(tool.execute(workbook, props));

        assertThat(data)
                .containsEntry("returned", 2)
                .containsEntry("truncated", true);
    }

    @Test
    void defaultLimitIsTen() {
        XSSFSheet sales = workbook.getSheet("Sales");
        for (int r = 6; r < 20; r++) {
            writeRow(sales, r, "Widget X", "North", r);
        }

        assertThat(data(tool.execute(workbook, props("A2:C20")))).containsEntry("returned", 10);
    }

    @Test
    void targetsSheetByNameAndByIndex() {
        Map<String, Object> byName = props("A1:C1");
        byName.put("sheetName", "Other");
        Map<String, Object> byIndex = props("A1:C1");
        byIndex.put("sheetIndex", 1);

        assertThat(rows(data(tool.execute(workbook, byName))).getFirst()).containsExactly("only", "row", 1L);
        assertThat(rows(data(tool.execute(workbook, byIndex))).getFirst()).containsExactly("only", "row", 1L);
    }

    @Test
    void rejectsUnknownOperator() {
        Map<String, Object> props = props("A2:C6");
        props.put("filters", List.of(Map.of("columnIndex", 0, "operator", "~", "value", "x")));

        assertThatThrownBy(() -> tool.execute(workbook, props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contains");
    }

    @Test
    void describeIsPlainLanguageAndNeverThrows() {
        Map<String, Object> sorted = props("A2:D20");
        sorted.put("sortColumnIndex", 2);
        sorted.put("ascending", false);
        sorted.put("limit", 5);

        assertThat(tool.describe(sorted)).isEqualTo("Found the 5 highest rows in A2:D20 by column C");
        assertThat(tool.describe(props("A2:D20"))).isEqualTo("Found up to 10 rows in A2:D20");
        assertThat(tool.describe(Map.of())).isEqualTo("Found up to 10 rows");

        assertThat(tool.describe(sorted, StepTense.IMPERATIVE))
                .isEqualTo("Find the 5 highest rows in A2:D20 by column C");
        assertThat(tool.describe(props("A2:D20"), StepTense.IMPERATIVE)).isEqualTo("Find up to 10 rows in A2:D20");
        assertThat(tool.describe(Map.of(), StepTense.IMPERATIVE)).isEqualTo("Find up to 10 rows");
    }

    @Test
    void promptSpecStartsWithTheToolName() {
        assertThat(tool.getType()).isEqualTo("FIND_ROWS");
        assertThat(tool.promptSpec()).startsWith("FIND_ROWS\n   Keys:").contains("sortColumnIndex");
    }

    private static Map<String, Object> props(String range) {
        Map<String, Object> props = new HashMap<>();
        props.put("range", range);
        return props;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(QueryResult result) {
        return (Map<String, Object>) result.data();
    }

    @SuppressWarnings("unchecked")
    private static List<List<Object>> rows(Map<String, Object> data) {
        return (List<List<Object>>) data.get("rows");
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
