package com.ap0stole.sheetsmith.services.excel.actions;

import com.ap0stole.sheetsmith.services.excel.actions.view.GroupRowsHandler;
import com.ap0stole.sheetsmith.services.excel.actions.view.PageSetupHandler;
import org.apache.poi.ss.usermodel.PrintSetup;
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

/**
 * The two actions that change how a sheet is read rather than what it says: which rows fold away,
 * and what comes out of a printer.
 */
class GroupRowsAndPageSetupTest {

    private XSSFWorkbook workbook;
    private XSSFSheet sheet;
    private final GroupRowsHandler group = new GroupRowsHandler();
    private final PageSetupHandler page = new PageSetupHandler();

    @BeforeEach
    void setUp() {
        workbook = new XSSFWorkbook();
        sheet = workbook.createSheet("Data");
        for (int r = 0; r < 10; r++) {
            sheet.createRow(r).createCell(0).setCellValue("row " + (r + 1));
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

    // ── GROUP_ROWS ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a row range is grouped, and the outline level says so")
    void groupsARowRange() throws Exception {
        String detail = group.execute(workbook, props("range", "3:6"));

        assertThat(sheet.getRow(2).getCTRow().getOutlineLevel()).isEqualTo((short) 1);
        assertThat(sheet.getRow(5).getCTRow().getOutlineLevel()).isEqualTo((short) 1);
        assertThat(sheet.getRow(6).getCTRow().getOutlineLevel())
                .as("the row after the group is outside it").isEqualTo((short) 0);
        assertThat(detail).isNull();
    }

    @Test
    @DisplayName("at plus count names the same rows a range would")
    void groupsFromAtAndCount() throws Exception {
        group.execute(workbook, props("at", 3, "count", 4));

        assertThat(sheet.getRow(2).getCTRow().getOutlineLevel()).isEqualTo((short) 1);
        assertThat(sheet.getRow(5).getCTRow().getOutlineLevel()).isEqualTo((short) 1);
        assertThat(sheet.getRow(6).getCTRow().getOutlineLevel()).isEqualTo((short) 0);
    }

    @Test
    @DisplayName("collapsing folds the grouped rows out of sight")
    void collapsesTheGroup() throws Exception {
        group.execute(workbook, props("range", "3:6", "collapsed", true));

        assertThat(sheet.getRow(2).getZeroHeight()).as("a collapsed row is hidden").isTrue();
        assertThat(sheet.getRow(5).getZeroHeight()).isTrue();
        assertThat(sheet.getRow(1).getZeroHeight()).as("rows outside the group stay visible").isFalse();
    }

    @Test
    @DisplayName("ungrouping takes the outline back off the same rows")
    void ungroupsAgain() throws Exception {
        group.execute(workbook, props("range", "3:6"));

        group.execute(workbook, props("range", "3:6", "ungroup", true));

        assertThat(sheet.getRow(2).getCTRow().getOutlineLevel()).isZero();
        assertThat(sheet.getRow(5).getCTRow().getOutlineLevel()).isZero();
    }

    @Test
    @DisplayName("a span past the end of the sheet is clamped and reported, not materialised")
    void clampsToWhatTheSheetHolds() throws Exception {
        String detail = group.execute(workbook, props("range", "8:5000"));

        assertThat(sheet.getLastRowNum())
                .as("POI's groupRow creates every row it is handed — 5000 of them would be here")
                .isEqualTo(9);
        assertThat(detail).contains("the sheet ends at row 10").contains("rows 11–5000");
    }

    @Test
    @DisplayName("a group starting past the end of the sheet does nothing and says so")
    void reportsAGroupWithNothingToGroup() throws Exception {
        String detail = group.execute(workbook, props("range", "50:60"));

        assertThat(detail).contains("the sheet ends at row 10");
        assertThat(sheet.getLastRowNum()).isEqualTo(9);
    }

    @Test
    @DisplayName("summaryBelow false moves the outline button above the detail rows")
    void honoursTotalsAboveTheDetail() throws Exception {
        group.execute(workbook, props("range", "3:6", "summaryBelow", false));

        assertThat(sheet.getRowSumsBelow()).isFalse();
    }

    @Test
    @DisplayName("a range that names no rows is refused with the fix")
    void refusesANonRowRange() {
        assertThatThrownBy(() -> group.execute(workbook, props("range", "A:C")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not name rows");
    }

    // ── PAGE_SETUP ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("landscape is set on the sheet's own print setup")
    void setsOrientation() throws Exception {
        page.execute(workbook, props("orientation", "landscape"));

        assertThat(sheet.getPrintSetup().getLandscape()).isTrue();
    }

    @Test
    @DisplayName("fitting to one page across leaves the height free, and turns page scaling on")
    void fitToWidthImpliesAnyHeightAndTheFitFlag() throws Exception {
        page.execute(workbook, props("fitToWidth", 1));

        PrintSetup print = sheet.getPrintSetup();
        assertThat(print.getFitWidth()).isEqualTo((short) 1);
        assertThat(print.getFitHeight())
                .as("0 is Excel's \"as many pages as it takes\"; POI's default of 1 would squash the sheet")
                .isEqualTo((short) 0);
        assertThat(sheet.getFitToPage())
                .as("without this flag Excel ignores fitToWidth entirely")
                .isTrue();
    }

    @Test
    @DisplayName("both bounds are kept when both are asked for")
    void fitToBothBounds() throws Exception {
        page.execute(workbook, props("fitToWidth", 1, "fitToHeight", 2));

        assertThat(sheet.getPrintSetup().getFitWidth()).isEqualTo((short) 1);
        assertThat(sheet.getPrintSetup().getFitHeight()).isEqualTo((short) 2);
    }

    @Test
    @DisplayName("the header row is set to repeat on every page")
    void setsRepeatingRows() throws Exception {
        page.execute(workbook, props("repeatHeaderRows", "1:1"));

        CellRangeAddress repeating = sheet.getRepeatingRows();
        assertThat(repeating).isNotNull();
        assertThat(repeating.getFirstRow()).isZero();
        assertThat(repeating.getLastRow()).isZero();
    }

    @Test
    @DisplayName("a bare row number is taken as that one row")
    void acceptsASingleRowForRepeating() throws Exception {
        page.execute(workbook, props("repeatHeaderRows", "1"));

        assertThat(sheet.getRepeatingRows().getLastRow()).isZero();
    }

    @Test
    @DisplayName("the print area is stored against the sheet")
    void setsPrintArea() throws Exception {
        page.execute(workbook, props("printArea", "A1:D40"));

        assertThat(workbook.getPrintArea(0)).contains("A1", "D40");
    }

    @Test
    @DisplayName("paper size and gridlines are set from plain words")
    void setsPaperSizeAndGridlines() throws Exception {
        page.execute(workbook, props("paperSize", "A4", "printGridlines", true));

        assertThat(sheet.getPrintSetup().getPaperSize()).isEqualTo(PrintSetup.A4_PAPERSIZE);
        assertThat(sheet.isPrintGridlines()).isTrue();
    }

    @Test
    @DisplayName("a step that asks for nothing is refused with the keys it could have used")
    void refusesAnEmptyStep() {
        assertThatThrownBy(() -> page.execute(workbook, props("sheetName", "Data")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fitToWidth");
    }

    @Test
    @DisplayName("an orientation nobody recognises names the two that work")
    void refusesAnUnknownOrientation() {
        assertThatThrownBy(() -> page.execute(workbook, props("orientation", "sideways-ish")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("landscape");
    }

    @Test
    @DisplayName("an unknown paper size names the ones that work")
    void refusesAnUnknownPaperSize() {
        assertThatThrownBy(() -> page.execute(workbook, props("paperSize", "A9")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A4");
    }
}
