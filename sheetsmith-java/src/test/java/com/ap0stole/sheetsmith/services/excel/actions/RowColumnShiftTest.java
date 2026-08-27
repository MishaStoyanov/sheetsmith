package com.ap0stole.sheetsmith.services.excel.actions;

import com.ap0stole.sheetsmith.services.excel.actions.chart.CreateChartHandler;
import com.ap0stole.sheetsmith.services.excel.actions.structure.DeleteColumnsHandler;
import com.ap0stole.sheetsmith.services.excel.actions.structure.DeleteRowsHandler;
import com.ap0stole.sheetsmith.services.excel.actions.structure.InsertColumnsHandler;
import com.ap0stole.sheetsmith.services.excel.actions.structure.InsertRowsHandler;
import com.ap0stole.sheetsmith.services.excel.actions.structure.StructureShift;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import org.apache.poi.ss.util.CellRangeAddress;
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
 * The four structural actions, and the thing that makes them the risky ones: they move cells other
 * steps are pointing at. What POI rewrites for us and what it leaves behind is pinned here, because
 * the catalog entries promise users a specific answer to "what happens to my formulas".
 */
class RowColumnShiftTest {

    private XSSFWorkbook workbook;
    private XSSFSheet sheet;
    private final InsertRowsHandler insertRows = new InsertRowsHandler();
    private final DeleteRowsHandler deleteRows = new DeleteRowsHandler();
    private final InsertColumnsHandler insertColumns = new InsertColumnsHandler();
    private final DeleteColumnsHandler deleteColumns = new DeleteColumnsHandler();

    @BeforeEach
    void setUp() {
        workbook = new XSSFWorkbook();
        sheet = workbook.createSheet("Data");
        // A1:C4 of values, with a total in A6 over them — the shape every one of these actions has
        // to keep intact.
        for (int r = 0; r < 4; r++) {
            XSSFRow row = sheet.createRow(r);
            for (int c = 0; c < 3; c++) {
                row.createCell(c).setCellValue((r + 1) * 10.0 + c);
            }
        }
        sheet.createRow(5).createCell(0).setCellFormula("SUM(A1:A4)");
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

    private double value(int row, int column) {
        return sheet.getRow(row).getCell(column).getNumericCellValue();
    }

    // ── INSERT_ROWS ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("inserting pushes the rows below down and leaves the new ones empty")
    void insertRowsMakesRoom() throws Exception {
        String detail = insertRows.execute(workbook, props("at", 2, "count", 2));

        assertThat(value(0, 0)).as("the row above the insert does not move").isEqualTo(10.0);
        assertThat((Object) sheet.getRow(1)).as("the new rows arrive empty, not styled copies").isNull();
        assertThat(value(3, 0)).as("what was row 2 is now row 4").isEqualTo(20.0);
        assertThat(detail).contains("2 rows inserted at row 2");
    }

    @Test
    @DisplayName("a formula over the moved rows follows them")
    void insertRowsRewritesFormulas() throws Exception {
        insertRows.execute(workbook, props("at", 2, "count", 2));

        assertThat(formula(7, 0)).isEqualTo("SUM(A1:A6)");
    }

    @Test
    @DisplayName("inserting past the end of the sheet is a no-op that says so")
    void insertRowsBelowTheContentDoesNothing() throws Exception {
        assertThat(insertRows.execute(workbook, props("at", 500)))
                .contains("already empty");
    }

    @Test
    @DisplayName("count is capped, so a misread sheet cannot cost a thousand rows")
    void insertRowsIsCapped() {
        assertThatThrownBy(() -> insertRows.execute(workbook, props("at", 2, "count", 5000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capped at " + StructureShift.MAX_INSERT);
    }

    @Test
    @DisplayName("\"at\" is a row number as Excel shows it, counting from 1")
    void insertRowsRefusesRowZero() {
        assertThatThrownBy(() -> insertRows.execute(workbook, props("at", 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and");
    }

    // ── DELETE_ROWS ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("deleting removes the rows and closes the gap")
    void deleteRowsClosesTheGap() throws Exception {
        String detail = deleteRows.execute(workbook, props("at", 2, "count", 2));

        assertThat(value(0, 0)).isEqualTo(10.0);
        assertThat(value(1, 0)).as("what was row 4 is now row 2").isEqualTo(40.0);
        assertThat(detail).contains("2 rows deleted");
    }

    @Test
    @DisplayName("a range names the rows just as well as at + count")
    void deleteRowsAcceptsARange() throws Exception {
        deleteRows.execute(workbook, props("range", "2:3"));

        assertThat(value(1, 0)).isEqualTo(40.0);
    }

    @Test
    @DisplayName("deleting the rows a formula adds up leaves that formula an error, and says so")
    void deleteRowsReportsBrokenFormulas() throws Exception {
        String detail = deleteRows.execute(workbook, props("range", "1:4"));

        assertThat(detail)
                .contains("4 rows deleted")
                .contains("error");
    }

    @Test
    @DisplayName("asking past the end deletes what is there and admits the sheet was shorter")
    void deleteRowsClampsToTheSheet() throws Exception {
        String detail = deleteRows.execute(workbook, props("at", 3, "count", 100));

        assertThat(detail).contains("the sheet ended at row 6");
        assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(2);
    }

    @Test
    @DisplayName("a deletion that names no target is refused rather than guessed")
    void deleteRowsNeedsATarget() {
        assertThatThrownBy(() -> deleteRows.execute(workbook, props()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\"at\" is required");
    }

    // ── INSERT_COLUMNS / DELETE_COLUMNS ───────────────────────────────────────

    @Test
    @DisplayName("inserting a column moves the ones right of it, by letter")
    void insertColumnsMakesRoom() throws Exception {
        String detail = insertColumns.execute(workbook, props("at", "B"));

        assertThat(value(0, 0)).isEqualTo(10.0);
        assertThat(sheet.getRow(0).getCell(1)).as("the new column arrives empty").isNull();
        assertThat(value(0, 2)).as("what was B is now C").isEqualTo(11.0);
        assertThat(detail).contains("1 column inserted at column B");
    }

    @Test
    @DisplayName("deleting a column closes the gap and the letters shift back")
    void deleteColumnsClosesTheGap() throws Exception {
        String detail = deleteColumns.execute(workbook, props("at", "B"));

        assertThat(value(0, 0)).isEqualTo(10.0);
        assertThat(value(0, 1)).as("what was C is now B").isEqualTo(12.0);
        assertThat(detail).contains("1 column deleted at column B");
    }

    @Test
    @DisplayName("\"B:C\" deletes both columns")
    void deleteColumnsAcceptsARange() throws Exception {
        deleteColumns.execute(workbook, props("range", "B:C"));

        assertThat(value(0, 0)).isEqualTo(10.0);
        assertThat(sheet.getRow(0).getCell(1)).isNull();
    }

    @Test
    @DisplayName("a column is named the way a spreadsheet names one, and a bad name is refused")
    void columnNamesAreValidated() {
        assertThatThrownBy(() -> insertColumns.execute(workbook, props("at", "nope!")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has to name a column");
    }

    // ── What moves with the rows, and what does not ───────────────────────────

    @Test
    @DisplayName("a merged region below the insert moves with its cells")
    void mergedRegionsFollowTheShift() throws Exception {
        sheet.addMergedRegion(new CellRangeAddress(3, 3, 0, 2));

        insertRows.execute(workbook, props("at", 2, "count", 1));

        CellRangeAddress merged = sheet.getMergedRegion(0);
        assertThat(merged.getFirstRow()).isEqualTo(4);
    }

    @Test
    @DisplayName("a formula on another sheet pointing here is rewritten too")
    void crossSheetFormulasAreRewritten() throws Exception {
        XSSFSheet summary = workbook.createSheet("Summary");
        summary.createRow(0).createCell(0).setCellFormula("Data!A4");

        insertRows.execute(workbook, props("at", 2, "count", 2));

        assertThat(summary.getRow(0).getCell(0).getCellFormula()).isEqualTo("Data!A6");
    }

    @Test
    @DisplayName("the frozen header stays frozen where it is — a pane is a view, not a range")
    void freezePanesAreNotMoved() throws Exception {
        sheet.createFreezePane(0, 1);

        insertRows.execute(workbook, props("at", 2, "count", 2));

        assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 1);
    }


    @Test
    @DisplayName("a chart keeps naming the cells it was drawn over — the one thing a shift cannot fix")
    void chartsAreNotFollowedAndTheStepSaysSo() throws Exception {
        new CreateChartHandler().execute(workbook, props(
                "sourceRange", "A1:B4", "chartType", "barChart", "title", "Totals",
                "chartWidth", 8, "chartHeight", 12, "targetSheet", "Data"));
        String drawn = workbook.getSheet("Data").getDrawingPatriarch().getCharts()
                .getFirst().getCTChart().toString();
        assertThat(drawn).contains("$A$1:$A$4");

        insertRows.execute(workbook, props("at", 2, "count", 2));

        // POI rewrites formulas across the workbook, but a chart's ranges live in the drawing part
        // rather than the formula table, so they stay where they were pointed. The catalog entry
        // tells the model to redraw a chart over cells it has moved; this is why.
        assertThat(workbook.getSheet("Data").getDrawingPatriarch().getCharts()
                .getFirst().getCTChart().toString())
                .as("if POI ever starts moving these, the catalog's advice needs revisiting")
                .contains("$A$1:$A$4");
    }


    @Test
    @DisplayName("a formula that was already broken and merely moved is not reported as new damage")
    void anErrorThatOnlyMovedIsNotReportedTwice() throws Exception {
        sheet.getRow(5).getCell(0).setCellFormula("SUM(A1:A4)");
        // Breaks the total, and reports it — the previous test's ground.
        assertThat(deleteRows.execute(workbook, props("range", "1:4"))).contains("error");

        // The broken total now moves sideways. Nothing new is wrong with the sheet.
        String detail = insertColumns.execute(workbook, props("at", "A"));

        assertThat(detail)
                .as("this was found by running it: the error moved from A2 to B2 and looked new")
                .doesNotContain("error");
    }

    // ── describe() ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the plan cards read as sentences, in the tense of the moment")
    void describeReadsAsASentence() {
        assertThat(insertRows.describe(props("at", 5, "count", 3), StepTense.IMPERATIVE))
                .isEqualTo("Insert 3 rows above row 5");
        assertThat(deleteRows.describe(props("range", "5:8"), StepTense.PAST))
                .isEqualTo("Deleted rows 5:8");
        assertThat(insertColumns.describe(props("at", "c"), StepTense.IMPERATIVE))
                .isEqualTo("Insert a column before column C");
        assertThat(deleteColumns.describe(props("at", "C", "count", 2), StepTense.PAST))
                .isEqualTo("Deleted 2 columns from C");
    }

    @Test
    @DisplayName("describe survives the keys a model gets wrong")
    void describeNeverThrows() {
        for (var handler : java.util.List.of(insertRows, deleteRows, insertColumns, deleteColumns)) {
            assertThat(handler.describe(Map.of(), StepTense.IMPERATIVE)).isNotBlank();
            assertThat(handler.describe(props("at", true, "count", "many"), StepTense.PAST)).isNotBlank();
        }
    }
}
