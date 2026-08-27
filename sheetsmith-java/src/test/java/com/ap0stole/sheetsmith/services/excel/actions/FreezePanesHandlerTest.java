package com.ap0stole.sheetsmith.services.excel.actions;

import com.ap0stole.sheetsmith.services.excel.actions.view.FreezePanesHandler;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import org.apache.poi.ss.util.PaneInformation;
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

class FreezePanesHandlerTest {

    private XSSFWorkbook workbook;
    private XSSFSheet sheet;
    private FreezePanesHandler handler;

    @BeforeEach
    void setUp() {
        workbook = new XSSFWorkbook();
        sheet = workbook.createSheet("Worksheet");
        handler = new FreezePanesHandler();

        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 3; c++) {
                sheet.createRow(r).createCell(c).setCellValue("r" + r + "c" + c);
            }
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        workbook.close();
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no keys means the intent behind the action: pin the header row")
    void freezesTheHeaderRowByDefault() throws Exception {
        assertThat(handler.execute(workbook, props())).isNull();

        PaneInformation pane = sheet.getPaneInformation();
        assertThat(pane).isNotNull();
        assertThat(pane.isFreezePane()).isTrue();
        assertThat(pane.getHorizontalSplitPosition()).isEqualTo((short) 1);
        assertThat(pane.getVerticalSplitPosition()).isEqualTo((short) 0);
    }

    @Test
    @DisplayName("a header on row 3 is rows: 3 — the count is the row number, so nothing is off by one")
    void freezesEverythingThroughTheHeaderRow() throws Exception {
        handler.execute(workbook, props("rows", 3));

        assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 3);
    }

    @Test
    void freezesRowsAndColumnsTogether() throws Exception {
        handler.execute(workbook, props("rows", 2, "columns", 1));

        PaneInformation pane = sheet.getPaneInformation();
        assertThat(pane.getHorizontalSplitPosition()).as("rows").isEqualTo((short) 2);
        assertThat(pane.getVerticalSplitPosition()).as("columns").isEqualTo((short) 1);
    }

    @Test
    @DisplayName("columns only, with no row split")
    void freezesColumnsAlone() throws Exception {
        handler.execute(workbook, props("rows", 0, "columns", 2));

        PaneInformation pane = sheet.getPaneInformation();
        assertThat(pane.getVerticalSplitPosition()).isEqualTo((short) 2);
        assertThat(pane.getHorizontalSplitPosition()).isEqualTo((short) 0);
    }

    // ── The way back ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("0,0 removes the pane")
    void unfreezes() throws Exception {
        handler.execute(workbook, props("rows", 2, "columns", 1));
        assertThat(sheet.getPaneInformation()).isNotNull();

        assertThat(handler.execute(workbook, props("rows", 0, "columns", 0))).isNull();
        assertThat(sheet.getPaneInformation()).isNull();
    }

    @Test
    @DisplayName("an explicit unfreeze works on an empty sheet too — the clamp must not swallow it")
    void unfreezesAnEmptySheet() throws Exception {
        XSSFSheet blank = workbook.createSheet("Blank");
        blank.createFreezePane(1, 1);
        assertThat(blank.getPaneInformation()).isNotNull();

        handler.execute(workbook, props("rows", 0, "columns", 0, "sheetName", "Blank"));

        assertThat(blank.getPaneInformation()).isNull();
    }

    // ── Clamping must never unfreeze ──────────────────────────────────────────

    @Test
    @DisplayName("freezing an empty sheet leaves an existing pane alone instead of removing it")
    void aFreezeRequestNeverRemovesAnExistingPane() throws Exception {
        XSSFSheet blank = workbook.createSheet("Blank");
        blank.createFreezePane(1, 1);

        String detail = handler.execute(workbook, props("rows", 1, "sheetName", "Blank"));

        PaneInformation pane = blank.getPaneInformation();
        assertThat(pane)
                .as("clamping to (0,0) would hit POI's unsetPane branch and destroy the user's pane")
                .isNotNull();
        // Not merely still present — still exactly what it was, so a partial clobber fails too.
        assertThat(pane.getHorizontalSplitPosition()).as("rows").isEqualTo((short) 1);
        assertThat(pane.getVerticalSplitPosition()).as("columns").isEqualTo((short) 1);
        assertThat(detail).isEqualTo("the sheet has nothing to freeze, so the panes were left as they were");
    }

    @Test
    @DisplayName("a split past the content leaves at least one row to scroll, and says so")
    void clampsASplitPastTheEndOfTheSheet() throws Exception {
        String detail = handler.execute(workbook, props("rows", 2_000_000));

        PaneInformation pane = sheet.getPaneInformation();
        assertThat(pane.getHorizontalSplitPosition())
                .as("freezing all 5 rows of a 5-row sheet leaves nothing to scroll")
                .isEqualTo((short) 4);
        assertThat(detail).isEqualTo("the sheet can keep at most 4 row(s) and 2 column(s) in view"
                + " and still scroll, so 4 row(s) and 0 column(s) were frozen");
    }

    @Test
    @DisplayName("clamping one axis still leaves a real split on the other")
    void aPartialClampStillFreezes() throws Exception {
        String detail = handler.execute(workbook, props("rows", 1, "columns", 99));

        PaneInformation pane = sheet.getPaneInformation();
        assertThat(pane.getHorizontalSplitPosition()).isEqualTo((short) 1);
        assertThat(pane.getVerticalSplitPosition()).isEqualTo((short) 2);
        assertThat(detail).contains("1 row(s) and 2 column(s) were frozen");
    }

    @Test
    void rejectsNegativeCounts() {
        assertThatThrownBy(() -> handler.execute(workbook, props("rows", -1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be negative");
    }

    // ── Wording ───────────────────────────────────────────────────────────────

    @Test
    void describesItself() {
        assertBothTenses(Map.of(), "Freeze the top row", "Froze the top row");
        assertBothTenses(props("rows", 3), "Freeze the top 3 rows", "Froze the top 3 rows");
        assertBothTenses(props("rows", 1, "columns", 1),
                "Freeze the top row and the first column",
                "Froze the top row and the first column");
        assertBothTenses(props("rows", 2, "columns", 3, "sheetName", "Sales"),
                "Freeze the top 2 rows and the first 3 columns on \"Sales\"",
                "Froze the top 2 rows and the first 3 columns on \"Sales\"");
        assertBothTenses(props("rows", 0, "columns", 2),
                "Freeze the first 2 columns", "Froze the first 2 columns");
        assertBothTenses(props("rows", 0, "columns", 0),
                "Unfreeze the panes", "Unfroze the panes");
    }

    @Test
    @DisplayName("a negative count is a freeze the step will reject, so the card must not read as an unfreeze")
    void neverDescribesARejectedRequestAsAnUnfreeze() {
        assertBothTenses(props("rows", -1), "Freeze the panes", "Froze the panes");
        assertBothTenses(props("rows", 1, "columns", -2), "Freeze the panes", "Froze the panes");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void assertBothTenses(Map<String, Object> properties, String imperative, String past) {
        assertThat(handler.describe(properties, StepTense.IMPERATIVE)).isEqualTo(imperative);
        assertThat(handler.describe(properties, StepTense.PAST)).isEqualTo(past);
    }

    private static Map<String, Object> props(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}
