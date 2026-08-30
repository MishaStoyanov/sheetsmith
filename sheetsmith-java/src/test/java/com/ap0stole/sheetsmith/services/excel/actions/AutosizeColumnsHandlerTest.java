package com.ap0stole.sheetsmith.services.excel.actions;

import com.ap0stole.sheetsmith.services.excel.actions.view.AutosizeColumnsHandler;
import com.ap0stole.sheetsmith.configs.ProcessingConfig;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

class AutosizeColumnsHandlerTest {

    private XSSFWorkbook workbook;
    private XSSFSheet sheet;
    private AutosizeColumnsHandler handler;
    private ProcessingConfig config;

    @BeforeEach
    void setUp() {
        workbook = new XSSFWorkbook();
        sheet = workbook.createSheet("Worksheet");
        config = new ProcessingConfig();
        handler = new AutosizeColumnsHandler(config);

        // A is long, B is left empty on purpose, C is short.
        text(0, 0, "A considerably longer heading than the default column width");
        text(0, 2, "Qty");
        text(1, 0, "Another long-ish value in the same column");
        text(1, 2, "7");
    }

    @AfterEach
    void tearDown() throws IOException {
        workbook.close();
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a column with long content ends up wider than it started")
    void widensToFitContent() throws Exception {
        int before = sheet.getColumnWidth(0);

        String detail = handler.execute(workbook, props());

        assertThat(sheet.getColumnWidth(0)).isGreaterThan(before);
        assertThat(detail).contains("resized");
    }

    @Test
    @DisplayName("\"A:C\" and \"A1:C99\" size the same columns, and neither touches D")
    void bothRangeSpellingsNameTheSameColumns() throws Exception {
        text(0, 3, "A fourth column with plenty of content to widen it");
        XSSFSheet twin = duplicate("Twin");
        int unsized = sheet.getColumnWidth(3);

        handler.execute(workbook, props("range", "A:C"));
        handler.execute(workbook, props("range", "A1:C99", "sheetName", "Twin"));

        for (int c = 0; c <= 3; c++) {
            assertThat(sheet.getColumnWidth(c))
                    .as("the whole-column and bounded spellings must agree on column %d", c)
                    .isEqualTo(twin.getColumnWidth(c));
        }
        assertThat(sheet.getColumnWidth(2)).as("C is inside the range").isNotEqualTo(unsized);
        assertThat(sheet.getColumnWidth(3)).as("D is outside both ranges").isEqualTo(unsized);
    }

    @Test
    @DisplayName("a title merged across A:E must not stretch column A")
    void ignoresMergedRegionsWhenMeasuring() throws Exception {
        String title = "A very long merged title that spans five whole columns indeed";
        XSSFSheet target = withMergedTitle("Merged", title);

        // The same sheet sized the other way round, as the control: this is what including merged
        // cells would produce, and it is the outcome the plain autoSizeColumn(c) call must avoid.
        XSSFSheet control = withMergedTitle("Control", title);
        control.autoSizeColumn(0, true);

        handler.execute(workbook, props("range", "A:A", "sheetName", "Merged"));

        assertThat(target.getColumnWidth(0))
                .as("MERGE_CELLS is the one existing action this interacts with, and a merged"
                        + " title driving column A's width is the failure")
                .isLessThan(control.getColumnWidth(0));
    }

    @Test
    @DisplayName("one very long cell cannot produce a column nobody can scroll past")
    void capsTheWidth() throws Exception {
        text(2, 0, "x".repeat(400));

        String detail = handler.execute(workbook, props("maxWidth", 20));

        assertThat(sheet.getColumnWidth(0)).isEqualTo(20 * 256);
        assertThat(detail).contains("capped at 20 characters");
    }

    // ── Count honesty ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("an empty column POI leaves alone is reported as unchanged, not resized")
    void doesNotClaimToHaveResizedAnEmptyColumn() throws Exception {
        int emptyBefore = sheet.getColumnWidth(1);

        String detail = handler.execute(workbook, props("range", "A:C"));

        assertThat(sheet.getColumnWidth(1)).as("POI leaves a column with no cells alone").isEqualTo(emptyBefore);
        assertThat(detail)
                .as("B did not move, so it must not be counted among the resized")
                .isEqualTo("2 columns resized, 1 already fitted");
    }

    @Test
    @DisplayName("a second run over already-sized columns claims nothing")
    void reportsNoChangeOnASecondRun() throws Exception {
        handler.execute(workbook, props("range", "A:A"));

        assertThat(handler.execute(workbook, props("range", "A:A")))
                .isEqualTo("no column needed a new width");
    }

    // ── The two diagnoses must not be confusable ──────────────────────────────

    @Test
    @DisplayName("a whole-row range is an input error and says so, naming the range")
    void aWholeRowRangeIsReportedAsAnInputError() {
        var properties = props("range", "1:1");
        assertThatThrownBy(() -> handler.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\"range\" has to name columns")
                .hasMessageContaining("1:1")
                .hasMessageContaining("A:D")
                // The regression: POI resolves "1:1" to column -1, autoSizeColumn(-1) throws, and the
                // broad catch used to turn that into a claim about the machine's fonts.
                .hasMessageNotContaining("fonts")
                .hasMessageNotContaining("measure text");
    }

    @Test
    @DisplayName("a column POI genuinely cannot measure is the only thing that blames the environment")
    void anUnmeasurableColumnIsReportedAsAnEnvironmentProblem() {
        XSSFSheet spied = spy(sheet);
        // How a JRE with no fonts installed actually fails inside AWT text measurement.
        doThrow(new NoClassDefFoundError("sun/awt/FontConfiguration")).when(spied).autoSizeColumn(anyInt());
        XSSFWorkbook spiedWorkbook = spy(workbook);
        doReturn(spied).when(spiedWorkbook).getSheetAt(0);

        var properties = props("range", "A:C");
        assertThatThrownBy(() -> handler.execute(spiedWorkbook, properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("None of the 3 column(s) could be measured")
                .hasMessageContaining("cannot measure text")
                .hasMessageNotContaining("\"range\"");
    }

    @Test
    @DisplayName("one column POI throws on is counted, not fatal — the RuntimeException arm")
    void survivesASingleUnmeasurableColumn() throws Exception {
        XSSFSheet spied = spy(sheet);
        doThrow(new IllegalStateException("no")).when(spied).autoSizeColumn(0);
        XSSFWorkbook spiedWorkbook = spy(workbook);
        doReturn(spied).when(spiedWorkbook).getSheetAt(0);

        String detail = handler.execute(spiedWorkbook, props("range", "A:C"));

        assertThat(detail).contains("1 left as it was (could not be measured)");
    }

    @Test
    @DisplayName("the measurement padding is put back, so a later step in the same turn is unaffected")
    void restoresTheMeasurementPadding() throws Exception {
        double before = sheet.getArbitraryExtraWidth();

        handler.execute(workbook, props("range", "A:C"));

        assertThat(sheet.getArbitraryExtraWidth()).isEqualTo(before);

        // And on the way out through a throw, which is what the finally block is for.
        XSSFSheet spied = spy(sheet);
        doThrow(new NoClassDefFoundError("sun/awt/FontConfiguration")).when(spied).autoSizeColumn(anyInt());
        XSSFWorkbook spiedWorkbook = spy(workbook);
        doReturn(spied).when(spiedWorkbook).getSheetAt(0);

        var properties = props("range", "A:C");
        assertThatThrownBy(() -> handler.execute(spiedWorkbook, properties))
                .isInstanceOf(IllegalStateException.class);
        assertThat(spied.getArbitraryExtraWidth()).isEqualTo(before);
    }

    // ── Bounds ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the budget stops the work part-way and reports the remainder, rather than refusing")
    void spendsTheBudgetAndReportsTheRemainder() throws Exception {
        // Two physical rows, so each column costs 2 — a budget of 2 buys exactly one column.
        config.setMaxAutosizeCells(2);
        int untouched = sheet.getColumnWidth(2);

        String detail = handler.execute(workbook, props("range", "A:C"));

        assertThat(sheet.getColumnWidth(0)).as("A was affordable").isNotEqualTo(2048);
        assertThat(sheet.getColumnWidth(2)).as("C was not reached").isEqualTo(untouched);
        assertThat(detail)
                .as("a refusal bounded nothing, since the model could just send A:A then B:C")
                .isEqualTo("1 column resized, 2 not measured (the measurement budget was spent"
                        + " — size them in a further step, or narrow the range)");
    }

    @Test
    @DisplayName("a budget too small for even one column still reports honestly instead of claiming success")
    void reportsWhenNothingWasAffordable() throws Exception {
        config.setMaxAutosizeCells(1);

        assertThat(handler.execute(workbook, props("range", "A:C")))
                .startsWith("no column was resized")
                .contains("3 not measured");
    }

    @Test
    @DisplayName("a whole-sheet spelling is charged for real content, not for 16384 empty columns")
    void clampsTheRangeToRealContent() throws Exception {
        config.setMaxAutosizeCells(100);

        assertThat(handler.execute(workbook, props("range", "A:XFD")))
                .as("only the 3 used columns are charged for, so the budget is never reached")
                .doesNotContain("not measured");
    }

    @Test
    @DisplayName("a formula is measured at its real result, not the stale 0 it was created with")
    void refreshesFormulasBeforeMeasuring() throws Exception {
        // The formula sits alone in column A, with its operands in B: anything else in A would drive
        // the width itself and hide whether the formula was ever evaluated.
        XSSFSheet target = withIsolatedTotal("Totals");

        // The same sheet sized the way POI does it unaided, as the control.
        XSSFSheet control = withIsolatedTotal("Control");
        control.autoSizeColumn(0);

        handler.execute(workbook, props("range", "A:A", "sheetName", "Totals"));

        assertThat(target.getColumnWidth(0))
                .as("unaided, POI measures the cached 0 and saves the real total into a column of ####")
                .isGreaterThan(control.getColumnWidth(0));
        assertThat(target.getRow(2).getCell(0).getCellFormula())
                .as("evaluateFormulaCell refreshes the cache and keeps the formula, unlike evaluateInCell")
                .isEqualTo("SUM(B1:B2)");
    }

    @Test
    void reportsAnEmptySheetRatherThanFailing() throws Exception {
        XSSFWorkbook empty = new XSSFWorkbook();
        empty.createSheet("Blank");

        assertThat(handler.execute(empty, props())).isEqualTo("the sheet is empty, so no column needed sizing");
        empty.close();
    }

    @Test
    @DisplayName("a malformed range is reported even when the sheet is empty")
    void parsesTheRangeBeforeCheckingForContent() throws Exception {
        XSSFWorkbook empty = new XSSFWorkbook();
        empty.createSheet("Blank");

        var properties = props("range", "1:1");
        assertThatThrownBy(() -> handler.execute(empty, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has to name columns");
        empty.close();
    }

    @Test
    void reportsARangeThatSitsPastTheContent() throws Exception {
        assertThat(handler.execute(workbook, props("range", "M:P")))
                .isEqualTo("those columns hold nothing, so no width changed");
    }

    // ── Wording ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the row parts of a range are noise when only its columns are used")
    void describesItself() {
        assertBothTenses(props("range", "A1:D20"),
                "Widen columns A:D to fit their contents",
                "Widened columns A:D to fit their contents");
        assertBothTenses(props("range", "A:A"),
                "Widen column A to fit its contents",
                "Widened column A to fit its contents");
        assertBothTenses(props("range", "B:D", "sheetName", "Sales"),
                "Widen columns B:D to fit their contents on \"Sales\"",
                "Widened columns B:D to fit their contents on \"Sales\"");
        assertBothTenses(Map.of(),
                "Widen every column to fit its contents",
                "Widened every column to fit its contents");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void assertBothTenses(Map<String, Object> properties, String imperative, String past) {
        assertThat(handler.describe(properties, StepTense.IMPERATIVE)).isEqualTo(imperative);
        assertThat(handler.describe(properties, StepTense.PAST)).isEqualTo(past);
    }

    /** A byte-for-byte content copy of the fixture sheet, so two spellings can be compared. */
    private XSSFSheet duplicate(String name) {
        XSSFSheet copy = workbook.createSheet(name);
        for (Row row : sheet) {
            Row target = copy.createRow(row.getRowNum());
            for (Cell cell : row) {
                target.createCell(cell.getColumnIndex()).setCellValue(cell.getStringCellValue());
            }
        }
        return copy;
    }

    /** A total in A whose operands live in B, so only the formula can set A's width. */
    private XSSFSheet withIsolatedTotal(String name) {
        XSSFSheet target = workbook.createSheet(name);
        target.createRow(0).createCell(1).setCellValue(123456789012345d);
        target.createRow(1).createCell(1).setCellValue(987654321098765d);
        // Created now, recalculated only at save — until then POI reads a cached 0.
        target.createRow(2).createCell(0).setCellFormula("SUM(B1:B2)");
        return target;
    }

    private XSSFSheet withMergedTitle(String name, String title) {
        XSSFSheet target = workbook.createSheet(name);
        target.createRow(0).createCell(0).setCellValue(title);
        target.addMergedRegion(new CellRangeAddress(0, 0, 0, 4));
        target.createRow(1).createCell(0).setCellValue("ok");
        return target;
    }

    private void text(int rowIdx, int colIdx, String value) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) row = sheet.createRow(rowIdx);
        Cell cell = row.getCell(colIdx);
        if (cell == null) cell = row.createCell(colIdx);
        cell.setCellValue(value);
    }

    private static Map<String, Object> props(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}
