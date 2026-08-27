package com.ap0stole.sheetsmith.services.excel.actions;

import com.ap0stole.sheetsmith.services.excel.actions.sheet.DeleteSheetHandler;
import com.ap0stole.sheetsmith.services.excel.actions.cell.UnmergeCellsHandler;
import com.ap0stole.sheetsmith.services.excel.StepTense;
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
 * The two actions that close asymmetries: ADD_SHEET had no delete, MERGE_CELLS had no unmerge.
 */
class SheetAndMergeTest {

    private XSSFWorkbook workbook;
    private XSSFSheet data;
    private final DeleteSheetHandler deleteSheet = new DeleteSheetHandler();
    private final UnmergeCellsHandler unmerge = new UnmergeCellsHandler();

    @BeforeEach
    void setUp() {
        workbook = new XSSFWorkbook();
        data = workbook.createSheet("Data");
        for (int r = 0; r < 4; r++) {
            var row = data.createRow(r);
            for (int c = 0; c < 3; c++) {
                row.createCell(c).setCellValue((r + 1) * 10.0 + c);
            }
        }
        XSSFSheet summary = workbook.createSheet("Summary");
        summary.createRow(0).createCell(0).setCellFormula("SUM(Data!A1:A4)");
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

    // ── DELETE_SHEET ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("the named sheet goes and the rest of the workbook stays")
    void deletesTheNamedSheet() throws Exception {
        String detail = deleteSheet.execute(workbook, props("name", "Summary"));

        assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
        assertThat(workbook.getSheetAt(0).getSheetName()).isEqualTo("Data");
        assertThat(detail).contains("\"Summary\" deleted").contains("1 sheet remains");
    }

    @Test
    @DisplayName("a formula that read from the deleted sheet is named before it is orphaned")
    void reportsFormulasThatLostTheirSource() throws Exception {
        String detail = deleteSheet.execute(workbook, props("name", "Data"));

        // Found by reading the formula text, not by the usual error scan: that scan decides a
        // formula is broken by evaluating it, and one naming a missing sheet does not evaluate to
        // an error, it fails to evaluate at all. Discovered by writing this test.
        assertThat(detail).contains("Summary!A1").contains("#REF!");
    }

    @Test
    @DisplayName("a sheet name needing quotes is still recognised in a formula")
    void findsReferencesToAQuotedSheetName() throws Exception {
        XSSFSheet spaced = workbook.createSheet("Old Data");
        spaced.createRow(0).createCell(0).setCellValue(1.0);
        workbook.getSheet("Summary").getRow(0).createCell(1).setCellFormula("'Old Data'!A1*2");

        assertThat(deleteSheet.execute(workbook, props("name", "Old Data")))
                .contains("Summary!B1");
    }

    @Test
    @DisplayName("\"sheetName\" works as well as \"name\" — a model reaches for both")
    void acceptsEitherKey() throws Exception {
        assertThat(deleteSheet.execute(workbook, props("sheetName", "Summary")))
                .contains("\"Summary\" deleted");
    }

    @Test
    @DisplayName("the last sheet cannot go: Excel refuses to open a workbook with none")
    void refusesToEmptyTheWorkbook() throws Exception {
        deleteSheet.execute(workbook, props("name", "Summary"));

        assertThatThrownBy(() -> deleteSheet.execute(workbook, props("name", "Data")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only sheet");
    }

    @Test
    @DisplayName("naming no sheet is refused — this is not an action to point at whatever is first")
    void refusesToGuessATarget() {
        assertThatThrownBy(() -> deleteSheet.execute(workbook, props()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\"name\" is required");
    }

    @Test
    @DisplayName("a sheet that is not there is named along with the ones that are")
    void namesTheAvailableSheets() {
        assertThatThrownBy(() -> deleteSheet.execute(workbook, props("name", "Nope")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\"Data\", \"Summary\"");
    }

    // ── UNMERGE_CELLS ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("with no range every merge on the sheet is split")
    void unmergesEverything() throws Exception {
        data.addMergedRegion(new CellRangeAddress(0, 0, 0, 2));
        data.addMergedRegion(new CellRangeAddress(2, 3, 0, 0));

        String detail = unmerge.execute(workbook, props("sheetName", "Data"));

        assertThat(data.getNumMergedRegions()).isZero();
        assertThat(detail).contains("2 merged regions split").contains("top-left cell");
    }

    @Test
    @DisplayName("a range splits every merge it touches and leaves the others alone")
    void unmergesOnlyWhatItTouches() throws Exception {
        data.addMergedRegion(new CellRangeAddress(0, 0, 0, 2));
        data.addMergedRegion(new CellRangeAddress(2, 3, 0, 0));

        unmerge.execute(workbook, props("range", "A1:C1", "sheetName", "Data"));

        assertThat(data.getNumMergedRegions()).isEqualTo(1);
        assertThat(data.getMergedRegion(0).formatAsString()).isEqualTo("A3:A4");
    }

    @Test
    @DisplayName("the value stays in the top-left cell, which is where it already was")
    void theValueSurvives() throws Exception {
        data.getRow(0).getCell(0).setCellValue("Quarterly report");
        data.addMergedRegion(new CellRangeAddress(0, 0, 0, 2));

        unmerge.execute(workbook, props("sheetName", "Data"));

        assertThat(data.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Quarterly report");
    }

    @Test
    @DisplayName("removing several regions at once takes out the right ones, not renumbered strangers")
    void removesTheRightRegionsWhenSeveralGo() throws Exception {
        data.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));
        data.addMergedRegion(new CellRangeAddress(1, 1, 0, 1));
        data.addMergedRegion(new CellRangeAddress(2, 2, 0, 1));

        unmerge.execute(workbook, props("range", "A1:B2", "sheetName", "Data"));

        assertThat(data.getNumMergedRegions()).isEqualTo(1);
        assertThat(data.getMergedRegion(0).formatAsString()).isEqualTo("A3:B3");
    }

    @Test
    @DisplayName("\"A:A\" is a fair way to say every merge in a column, and costs nothing")
    void wholeColumnsAreAllowedHere() throws Exception {
        data.addMergedRegion(new CellRangeAddress(2, 3, 0, 0));
        data.addMergedRegion(new CellRangeAddress(0, 0, 1, 2));

        unmerge.execute(workbook, props("range", "A:A", "sheetName", "Data"));

        assertThat(data.getNumMergedRegions()).isEqualTo(1);
        assertThat(data.getMergedRegion(0).formatAsString()).isEqualTo("B1:C1");
    }

    @Test
    @DisplayName("nothing merged is a normal result, said plainly")
    void nothingToUnmergeIsNotAnError() throws Exception {
        assertThat(unmerge.execute(workbook, props("sheetName", "Data")))
                .contains("no merged cells");
    }

    @Test
    @DisplayName("a range overlapping no merge says so rather than claiming work")
    void aRangeWithNoMergesSaysSo() throws Exception {
        data.addMergedRegion(new CellRangeAddress(0, 0, 0, 2));

        assertThat(unmerge.execute(workbook, props("range", "A3:C4", "sheetName", "Data")))
                .contains("no merged cells overlap");
    }

    // ── describe() ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the plan cards read as sentences")
    void describeReadsAsASentence() {
        assertThat(deleteSheet.describe(props("name", "Old data"), StepTense.IMPERATIVE))
                .isEqualTo("Delete the sheet \"Old data\"");
        assertThat(unmerge.describe(props(), StepTense.IMPERATIVE))
                .isEqualTo("Unmerge every merged block");
        assertThat(unmerge.describe(props("range", "A1:C1"), StepTense.PAST))
                .isEqualTo("Unmerged A1:C1");
    }

    @Test
    @DisplayName("describe survives the keys a model gets wrong")
    void describeNeverThrows() {
        assertThat(deleteSheet.describe(Map.of(), StepTense.PAST)).isNotBlank();
        assertThat(unmerge.describe(props("range", 9), StepTense.PAST)).isNotBlank();
    }
}
