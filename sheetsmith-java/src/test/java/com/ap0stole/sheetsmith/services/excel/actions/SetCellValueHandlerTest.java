package com.ap0stole.sheetsmith.services.excel.actions;

import com.ap0stole.sheetsmith.services.excel.actions.cell.SetCellValueHandler;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFFont;
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
 * The handler over a real workbook. The cases that matter are the type rules — a literal must come
 * back as the literal that was sent — and the promise that a write changes the value and nothing else
 * about the cell.
 */
class SetCellValueHandlerTest {

    private XSSFWorkbook workbook;
    private XSSFSheet sheet;
    private SetCellValueHandler handler;
    private int scratchCount;

    @BeforeEach
    void setUp() {
        workbook = new XSSFWorkbook();
        sheet = workbook.createSheet("Worksheet");
        handler = new SetCellValueHandler();
    }

    @AfterEach
    void tearDown() throws IOException {
        workbook.close();
    }

    // ── The hole this action fills ────────────────────────────────────────────

    @Test
    @DisplayName("a literal lands in a cell that did not exist before")
    void writesIntoAnEmptyCell() throws Exception {
        String detail = handler.execute(workbook, props("cell", "A1", "value", "Q1 2026"));

        assertThat(cell(0, 0).getCellType()).isEqualTo(CellType.STRING);
        assertThat(cell(0, 0).getStringCellValue()).isEqualTo("Q1 2026");
        assertThat(detail).as("one clean cell needs no count — describe() said it all").isNull();
    }

    // ── Type rules ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a leading-zero string stays the string the user wanted kept")
    void keepsLeadingZeroes() throws Exception {
        handler.execute(workbook, props("cell", "A1", "value", "007"));

        assertThat(cell(0, 0).getCellType()).isEqualTo(CellType.STRING);
        assertThat(cell(0, 0).getStringCellValue()).isEqualTo("007");
    }

    @Test
    @DisplayName("a quoted number becomes a number only when it renders back identically")
    void coercesOnlyOnAnExactRoundTrip() throws Exception {
        assertThat(writeAndRead("42")).isEqualTo(42.0);
        assertThat(writeAndRead("0")).isEqualTo(0.0);
        assertThat(writeAndRead("-3.5")).isEqualTo(-3.5);

        // Each of these renders back as something else, so the literal survives as text.
        assertThat(writeAndRead("1.50")).isEqualTo("1.50");
        assertThat(writeAndRead("42.0")).isEqualTo("42.0");
        assertThat(writeAndRead("1e5")).isEqualTo("1e5");
        assertThat(writeAndRead("+1 (682) 897-1263")).isEqualTo("+1 (682) 897-1263");
    }

    @Test
    @DisplayName("the JSON type the model sent is honoured without a round-trip test")
    void jsonTypesAreAuthoritative() throws Exception {
        assertThat(writeAndRead(1234.56)).isEqualTo(1234.56);
        assertThat(writeAndRead(7)).isEqualTo(7.0);
        assertThat(writeAndRead(true)).isEqualTo(true);
    }

    @Test
    void quotedBooleansBecomeBooleans() throws Exception {
        assertThat(writeAndRead("true")).isEqualTo(true);
        assertThat(writeAndRead("FALSE")).isEqualTo(false);
    }

    @Test
    @DisplayName("valueType overrides the inference in both directions")
    void explicitValueTypeWins() throws Exception {
        handler.execute(workbook, props("cell", "A1", "value", 42, "valueType", "text"));
        assertThat(cell(0, 0).getCellType()).isEqualTo(CellType.STRING);
        assertThat(cell(0, 0).getStringCellValue()).isEqualTo("42");

        handler.execute(workbook, props("cell", "A2", "value", "007", "valueType", "number"));
        assertThat(cell(1, 0).getCellType()).isEqualTo(CellType.NUMERIC);
        assertThat(cell(1, 0).getNumericCellValue()).isEqualTo(7.0);
    }

    @Test
    @DisplayName("a date-looking string is text until valueType says otherwise")
    void datesAreNeverGuessed() throws Exception {
        handler.execute(workbook, props("cell", "A1", "value", "2026-01-31"));

        assertThat(cell(0, 0).getCellType()).isEqualTo(CellType.STRING);
        assertThat(cell(0, 0).getStringCellValue()).isEqualTo("2026-01-31");
    }

    @Test
    @DisplayName("an explicit date is stored as a date and formatted, or it reads as a serial number")
    void writesAFormattedDate() throws Exception {
        handler.execute(workbook, props("cell", "A1", "value", "2026-01-31", "valueType", "date"));

        assertThat(cell(0, 0).getCellType()).isEqualTo(CellType.NUMERIC);
        assertThat(DateUtil.isCellDateFormatted(cell(0, 0))).isTrue();
        assertThat(cell(0, 0).getLocalDateTimeCellValue().toLocalDate()).hasToString("2026-01-31");
    }

    @Test
    @DisplayName("a date-time keeps its time, and is formatted to show it")
    void writesADateTime() throws Exception {
        handler.execute(workbook, props("cell", "A1", "value", "2026-01-31T14:30:00", "valueType", "datetime"));

        assertThat(DateUtil.isCellDateFormatted(cell(0, 0))).isTrue();
        assertThat(cell(0, 0).getLocalDateTimeCellValue()).hasToString("2026-01-31T14:30");
        assertThat(cell(0, 0).getCellStyle().getDataFormatString())
                .as("a date-only format would hide the time entirely")
                .isEqualTo("yyyy-mm-dd hh:mm:ss");
    }

    @Test
    @DisplayName("a date and a date-time in the same sheet each keep their own format")
    void theTwoDatePatternsCoexist() throws Exception {
        handler.execute(workbook, props("cell", "A1", "value", "2026-01-31", "valueType", "date"));
        handler.execute(workbook, props("cell", "A2", "value", "2026-01-31T14:30:00", "valueType", "date"));

        assertThat(cell(0, 0).getCellStyle().getDataFormatString()).isEqualTo("yyyy-mm-dd");
        assertThat(cell(1, 0).getCellStyle().getDataFormatString()).isEqualTo("yyyy-mm-dd hh:mm:ss");
    }

    @Test
    @DisplayName("a cell already showing dates keeps the format its owner chose")
    void leavesAnExistingDateFormatAlone() throws Exception {
        CellStyle ukDate = workbook.createCellStyle();
        ukDate.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("dd/mm/yyyy"));
        cell(0, 0).setCellStyle(ukDate);
        int styles = workbook.getNumCellStyles();

        handler.execute(workbook, props("cell", "A1", "value", "2026-01-31", "valueType", "date"));

        assertThat(cell(0, 0).getCellStyle().getDataFormatString()).isEqualTo("dd/mm/yyyy");
        assertThat(workbook.getNumCellStyles())
                .as("no style needs deriving when the cell already displays dates")
                .isEqualTo(styles);
    }

    @Test
    void rejectsANonIsoDate() {
        var properties = props("cell", "A1", "value", "31/01/2026", "valueType", "date");
        assertThatThrownBy(() -> handler.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not an ISO date");
    }

    @Test
    @DisplayName("POI turns a non-finite double into an error cell, so it is refused before the write")
    void rejectsNonFiniteNumbers() {
        for (String value : new String[]{"NaN", "Infinity", "-Infinity"}) {
            var properties = props("cell", "A1", "value", value, "valueType", "number");
            assertThatThrownBy(() -> handler.execute(workbook, properties))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is not a finite number");
        }
        assertThat(sheet.getPhysicalNumberOfRows()).as("nothing was written").isZero();
    }

    @Test
    @DisplayName("the auto path was already safe: NaN never round-trips, so it stays text")
    void nonFiniteViaTheAutoPathStaysText() throws Exception {
        assertThat(writeAndRead("NaN")).isEqualTo("NaN");
    }

    // ── Nothing but the value changes ─────────────────────────────────────────

    @Test
    @DisplayName("a styled cell keeps its fill — a write is not a reformat")
    void preservesExistingStyle() throws Exception {
        CellStyle yellow = workbook.createCellStyle();
        yellow.setFillForegroundColor(IndexedColors.YELLOW.getIndex());
        yellow.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        cell(0, 0).setCellStyle(yellow);

        handler.execute(workbook, props("cell", "A1", "value", "Total"));

        assertThat(cell(0, 0).getCellStyle().getFillForegroundColor())
                .isEqualTo(IndexedColors.YELLOW.getIndex());
        assertThat(cell(0, 0).getCellStyle().getFillPattern()).isEqualTo(FillPatternType.SOLID_FOREGROUND);
    }

    @Test
    @DisplayName("a date write adds a format by cloning, so the cell's own styling survives it")
    void aDateKeepsTheCellsOtherStyling() throws Exception {
        CellStyle yellow = workbook.createCellStyle();
        yellow.setFillForegroundColor(IndexedColors.YELLOW.getIndex());
        yellow.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        cell(0, 0).setCellStyle(yellow);

        handler.execute(workbook, props("cell", "A1", "value", "2026-01-31", "valueType", "date"));

        assertThat(DateUtil.isCellDateFormatted(cell(0, 0))).isTrue();
        assertThat(cell(0, 0).getCellStyle().getFillForegroundColor())
                .as("the fill is not ours to discard")
                .isEqualTo(IndexedColors.YELLOW.getIndex());
    }

    @Test
    @DisplayName("a destroyed formula is reported, because describe() only mentions the value written")
    void reportsThatAFormulaWasReplaced() throws Exception {
        cell(0, 1).setCellFormula("SUM(A1:A9)");

        String detail = handler.execute(workbook, props("cell", "B1", "value", 100));

        assertThat(detail)
                .as("a silent formula deletion is a card the user approves without being told")
                .isEqualTo("1 cell set, replacing the formula it held");
    }

    @Test
    @DisplayName("a fill over several formulas counts them")
    void countsEveryFormulaItReplaces() throws Exception {
        cell(0, 0).setCellFormula("1+1");
        cell(1, 0).setCellFormula("2+2");

        String detail = handler.execute(workbook, props("range", "A1:A3", "value", 0));

        assertThat(detail).isEqualTo("3 cells set, replacing 2 formulas");
    }

    @Test
    @DisplayName("a plain write over a plain cell still says nothing extra")
    void staysSilentWhenNoFormulaWasHarmed() throws Exception {
        cell(0, 0).setCellValue("old");

        assertThat(handler.execute(workbook, props("cell", "A1", "value", "new"))).isNull();
    }

    @Test
    @DisplayName("a multi-cell array formula is refused in words a user can act on")
    void refusesToBreakUpAnArrayFormula() {
        sheet.setArrayFormula("A1:A2*2", CellRangeAddress.valueOf("B1:B2"));

        var properties = props("cell", "B2", "value", 5);
        assertThatThrownBy(() -> handler.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("B2")
                .hasMessageContaining("array formula")
                .as("POI's own wording is not something to show a user")
                .hasMessageNotContaining("You cannot change part of an array");
    }

    @Test
    @DisplayName("overwriting a formula removes the formula and keeps the style")
    void replacesAFormulaRatherThanItsCachedResult() throws Exception {
        XSSFFont bold = workbook.createFont();
        bold.setBold(true);
        CellStyle boldStyle = workbook.createCellStyle();
        boldStyle.setFont(bold);
        Cell target = cell(0, 0);
        target.setCellFormula("1+1");
        target.setCellStyle(boldStyle);

        handler.execute(workbook, props("cell", "A1", "value", "fixed"));

        assertThat(cell(0, 0).getCellType())
                .as("setCellValue alone would leave a FORMULA cell with a new cached result")
                .isEqualTo(CellType.STRING);
        assertThat(cell(0, 0).getStringCellValue()).isEqualTo("fixed");
        assertThat(workbook.getFontAt(cell(0, 0).getCellStyle().getFontIndex()).getBold()).isTrue();
    }

    @Test
    @DisplayName("one style is derived per distinct source style, not per cell")
    void reusesTheDerivedDateStyle() throws Exception {
        int before = workbook.getNumCellStyles();

        handler.execute(workbook, props("range", "A1:A20", "value", "2026-01-31", "valueType", "date"));

        assertThat(workbook.getNumCellStyles() - before)
                .as("20 cells share one source style, so one derived style is enough")
                .isEqualTo(1);
    }

    // ── Ranges and counting ───────────────────────────────────────────────────

    @Test
    @DisplayName("a range fill reports how many cells it set")
    void fillsARangeAndCounts() throws Exception {
        String detail = handler.execute(workbook, props("range", "A1:C2", "value", "N/A"));

        assertThat(detail).isEqualTo("6 cells set");
        assertThat(cell(1, 2).getStringCellValue()).isEqualTo("N/A");
    }

    @Test
    @DisplayName("cells hidden inside a merge are skipped and counted, never silently written")
    void reportsCellsSwallowedByAMerge() throws Exception {
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 2));

        String detail = handler.execute(workbook, props("range", "A1:C1", "value", "Q1 2026"));

        assertThat(cell(0, 0).getStringCellValue()).isEqualTo("Q1 2026");
        assertThat(detail).isEqualTo(
                "1 cell set, 2 left alone (inside a merged region, where the value would not show)");
    }

    @Test
    @DisplayName("a write that would be entirely invisible fails instead of reporting success")
    void refusesAWriteThatWouldShowNothing() {
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 2));

        var properties = props("cell", "B1", "value", "hidden");
        assertThatThrownBy(() -> handler.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("top-left cell");
    }

    @Test
    @DisplayName("sheetIndex targets a sheet just as sheetName does")
    void targetsASheetByIndex() throws Exception {
        XSSFSheet second = workbook.createSheet("Second");

        handler.execute(workbook, props("cell", "A1", "value", "by index", "sheetIndex", 1));

        assertThat(second.getRow(0).getCell(0).getStringCellValue()).isEqualTo("by index");
        assertThat(sheet.getPhysicalNumberOfRows()).as("the first sheet is untouched").isZero();
    }

    @Test
    @DisplayName("the cap advises splitting the fill, since TRANSFORM_COLUMN cannot write a literal")
    void refusesAFillLargerThanTheCap() {
        var properties = props("range", "A1:A10001", "value", "x");
        assertThatThrownBy(() -> handler.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("split the fill into steps of 10000 cells or fewer")
                // TRANSFORM_COLUMN skips blanks, so it cannot do the fill that trips this cap; it is
                // named only for the job it can actually do.
                .hasMessageContaining("rewrite values the column already holds");
    }

    // ── The degenerate references POI resolves to -1 ──────────────────────────

    @Test
    @DisplayName("a whole-column reference is refused, not passed to POI as row -1")
    void refusesAWholeColumnReference() {
        var properties = props("cell", "A:A", "value", "Region");
        assertThatThrownBy(() -> handler.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole column")
                .hasMessageContaining("A1:A20");
    }

    @Test
    @DisplayName("a whole-row reference is refused, not passed to POI as column -1")
    void refusesAWholeRowReference() {
        var properties = props("cell", "1:1", "value", "Region");
        assertThatThrownBy(() -> handler.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole row");
    }

    @Test
    void requiresACellAndAValue() {
        var properties = props("value", "orphan");
        assertThatThrownBy(() -> handler.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\"cell\" is required");

        var properties2 = props("cell", "A1");
        assertThatThrownBy(() -> handler.execute(workbook, properties2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\"value\" is required");
    }

    @Test
    void rejectsAnUnknownValueType() {
        var properties = props("cell", "A1", "value", "x", "valueType", "currency");
        assertThatThrownBy(() -> handler.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text, number, boolean or date");
    }

    // ── Wording ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("quoting follows what will actually be written, not the JSON type")
    void describesItself() {
        assertBothTenses(props("cell", "A1", "value", "Q1 2026", "sheetName", "Sales"),
                "Write \"Q1 2026\" into A1 on \"Sales\"",
                "Wrote \"Q1 2026\" into A1 on \"Sales\"");

        assertBothTenses(props("cell", "B2", "value", 42),
                "Write 42 into B2", "Wrote 42 into B2");

        // Quoted in the sentence because a text cell is what the step will produce.
        assertBothTenses(props("cell", "B2", "value", 42, "valueType", "text"),
                "Write \"42\" into B2", "Wrote \"42\" into B2");

        assertBothTenses(props("cell", "C3", "value", "007"),
                "Write \"007\" into C3", "Wrote \"007\" into C3");
    }

    @Test
    void describesIncompleteProperties() {
        assertBothTenses(Map.of(), "Write a value into the cell", "Wrote a value into the cell");
        assertBothTenses(props("range", "A1:C1", "value", "N/A"),
                "Write \"N/A\" into A1:C1", "Wrote \"N/A\" into A1:C1");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Writes into a cell of its own and reads back what a user would see there. */
    private Object writeAndRead(Object value) throws Exception {
        String name = "Scratch" + scratchCount++;
        XSSFSheet scratch = workbook.createSheet(name);
        handler.execute(workbook, props("cell", "A1", "value", value, "sheetName", name));

        Cell written = scratch.getRow(0).getCell(0);
        return switch (written.getCellType()) {
            case NUMERIC -> written.getNumericCellValue();
            case BOOLEAN -> written.getBooleanCellValue();
            default -> written.getStringCellValue();
        };
    }

    private void assertBothTenses(Map<String, Object> properties, String imperative, String past) {
        assertThat(handler.describe(properties, StepTense.IMPERATIVE)).isEqualTo(imperative);
        assertThat(handler.describe(properties, StepTense.PAST)).isEqualTo(past);
    }

    private Cell cell(int rowIdx, int colIdx) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) row = sheet.createRow(rowIdx);
        Cell cell = row.getCell(colIdx);
        return cell == null ? row.createCell(colIdx) : cell;
    }

    private static Map<String, Object> props(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}
