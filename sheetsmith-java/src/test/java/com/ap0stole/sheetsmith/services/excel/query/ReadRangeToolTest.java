package com.ap0stole.sheetsmith.services.excel.query;

import com.ap0stole.sheetsmith.configs.ChatConfig;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadRangeToolTest {

    private XSSFWorkbook workbook;
    private ChatConfig chatConfig;
    private ReadRangeTool tool;

    @BeforeEach
    void setUp() {
        workbook = new XSSFWorkbook();
        XSSFSheet sales = workbook.createSheet("Sales");
        XSSFRow header = sales.createRow(0);
        header.createCell(0).setCellValue("Product");
        header.createCell(1).setCellValue("Units");
        header.createCell(2).setCellValue("Active");
        XSSFRow data = sales.createRow(1);
        data.createCell(0).setCellValue("Widget A");
        data.createCell(1).setCellValue(100);
        data.createCell(2).setCellValue(true);
        data.createCell(3).setCellFormula("B2*2");

        XSSFSheet other = workbook.createSheet("Other");
        other.createRow(0).createCell(0).setCellValue("from other");

        chatConfig = new ChatConfig();
        tool = new ReadRangeTool(chatConfig);
    }

    @AfterEach
    void tearDown() throws IOException {
        workbook.close();
    }

    @Test
    void returnsTypedValuesAndResolvesFormulas() {
        QueryResult result = tool.execute(workbook, Map.of("range", "A1:D2"));

        Map<String, Object> data = data(result);
        assertThat(data)
                .containsEntry("sheet", "Sales")
                .containsEntry("range", "A1:D2");

        List<List<Object>> values = values(data);
        assertThat(values).hasSize(2);
        assertThat(values.get(0)).containsExactly("Product", "Units", "Active", null);
        assertThat(values.get(1)).containsExactly("Widget A", 100L, true, 200L);
        assertThat(result.summary()).contains("A1:D2").contains("Sales");
    }

    @Test
    void targetsSheetByNameAndByIndex() {
        QueryResult byName = tool.execute(workbook, Map.of("range", "A1:A1", "sheetName", "Other"));
        QueryResult byIndex = tool.execute(workbook, Map.of("range", "A1:A1", "sheetIndex", 1));

        assertThat(values(data(byName)).getFirst()).containsExactly("from other");
        assertThat(values(data(byIndex)).getFirst()).containsExactly("from other");
        assertThat(data(byIndex).get("sheet")).isEqualTo("Other");
    }

    @Test
    void nameWinsOverIndex() {
        QueryResult result = tool.execute(workbook, Map.of("range", "A1:A1", "sheetName", "Other", "sheetIndex", 0));

        assertThat(data(result).get("sheet")).isEqualTo("Other");
    }

    @Test
    void toleratesMissingRowsAndCells() {
        QueryResult result = tool.execute(workbook, Map.of("range", "A1:B4"));

        assertThat(values(data(result))).hasSize(4);
        assertThat(values(data(result)).get(3)).containsExactly(null, null);
    }

    @Test
    void rejectsRangesOverTheCellBudget() {
        assertThatThrownBy(() -> tool.execute(workbook, Map.of("range", "A1:Z9999")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("covers 259974 cells")
                .hasMessageContaining("limit is 300")
                .hasMessageContaining("AGGREGATE");
    }

    @Test
    void capIsDrivenByChatConfig() {
        chatConfig.setMaxCells(4);

        assertThat(tool.execute(workbook, Map.of("range", "A1:B2"))).isNotNull();
        assertThatThrownBy(() -> tool.execute(workbook, Map.of("range", "A1:C2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit is 4");
    }

    @Test
    void describeIsPlainLanguageAndNeverThrows() {
        assertThat(tool.describe(Map.of("range", "A1:C10", "sheetName", "Sales")))
                .isEqualTo("Read A1:C10 on \"Sales\"");
        assertThat(tool.describe(Map.of("range", "A1:C10", "sheetIndex", 1))).isEqualTo("Read A1:C10 on sheet 2");
        assertThat(tool.describe(Map.of())).isEqualTo("Read a range");

        assertThat(tool.describe(Map.of("range", "A1:C10", "sheetName", "Sales"), StepTense.IMPERATIVE))
                .isEqualTo("Read A1:C10 on \"Sales\"");
        assertThat(tool.describe(Map.of(), StepTense.IMPERATIVE)).isEqualTo("Read a range");
    }

    @Test
    void promptSpecStartsWithTheToolName() {
        assertThat(tool.getType()).isEqualTo("READ_RANGE");
        assertThat(tool.promptSpec()).startsWith("READ_RANGE\n   Keys:").contains("300");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(QueryResult result) {
        return (Map<String, Object>) result.data();
    }

    @SuppressWarnings("unchecked")
    private static List<List<Object>> values(Map<String, Object> data) {
        return (List<List<Object>>) data.get("values");
    }
}
