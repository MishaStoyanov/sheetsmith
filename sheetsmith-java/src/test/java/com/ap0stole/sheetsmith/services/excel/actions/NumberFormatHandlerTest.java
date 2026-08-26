package com.ap0stole.sheetsmith.services.excel.actions;

import com.ap0stole.sheetsmith.services.excel.StepTense;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
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

class NumberFormatHandlerTest {

    private XSSFWorkbook workbook;
    private XSSFSheet sheet;
    private NumberFormatHandler handler;

    @BeforeEach
    void setUp() {
        workbook = new XSSFWorkbook();
        sheet = workbook.createSheet("Worksheet");
        handler = new NumberFormatHandler();

        sheet.createRow(0).createCell(0).setCellValue("Amount");
        for (int r = 1; r <= 3; r++) {
            sheet.createRow(r).createCell(0).setCellValue(1234.5678 * r);
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        workbook.close();
    }

    private String format(int row) {
        return sheet.getRow(row).getCell(0).getCellStyle().getDataFormatString();
    }

    private Map<String, Object> props(Object... pairs) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            properties.put((String) pairs[i], pairs[i + 1]);
        }
        return properties;
    }

    // ── The named formats ─────────────────────────────────────────────────────

    @Test
    @DisplayName("currency formats to two decimals and a symbol, and leaves the number a number")
    void currencyKeepsTheValueNumeric() throws Exception {
        assertThat(handler.execute(workbook, props("range", "A2:A4", "format", "currency"))).isNull();

        assertThat(format(1)).isEqualTo("\"$\"#,##0.00");
        assertThat(sheet.getRow(1).getCell(0).getCellType())
                .as("a number format changes how a value reads, never what it is")
                .isEqualTo(CellType.NUMERIC);
        assertThat(sheet.getRow(1).getCell(0).getNumericCellValue()).isEqualTo(1234.5678);
    }

    @Test
    @DisplayName("\"decimals\" overrides the places, and \"currencySymbol\" the symbol")
    void decimalsAndSymbolAreHonoured() throws Exception {
        handler.execute(workbook, props("range", "A2:A4", "format", "currency",
                "decimals", 0, "currencySymbol", "€"));

        assertThat(format(1)).isEqualTo("\"€\"#,##0");
    }

    @Test
    @DisplayName("percent defaults to whole numbers — 0.15 reads as 15%, not 15.00%")
    void percentDefaultsToNoDecimals() throws Exception {
        handler.execute(workbook, props("range", "A2:A4", "format", "percent"));

        assertThat(format(1)).isEqualTo("0%");
    }

    @Test
    @DisplayName("date, text and general each resolve to their Excel pattern")
    void theOtherNamesResolve() throws Exception {
        handler.execute(workbook, props("range", "A2:A2", "format", "date"));
        handler.execute(workbook, props("range", "A3:A3", "format", "text"));
        handler.execute(workbook, props("range", "A4:A4", "format", "general"));

        assertThat(format(1)).isEqualTo("yyyy-mm-dd");
        assertThat(format(2)).isEqualTo("@");
        assertThat(format(3)).isEqualTo("General");
    }

    @Test
    @DisplayName("a literal Excel pattern is taken as written")
    void aLiteralPatternIsAccepted() throws Exception {
        handler.execute(workbook, props("range", "A2:A4", "format", "#,##0.000"));

        assertThat(format(1)).isEqualTo("#,##0.000");
    }

    // ── What it refuses ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a pattern longer than Excel allows is refused before it reaches the file")
    void anOverlongPatternIsRefused() {
        assertThatThrownBy(() -> handler.execute(workbook, props("range", "A2:A4", "format", "0".repeat(256))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("255");
    }

    @Test
    @DisplayName("no format, no step: the key is what the action is")
    void theFormatKeyIsRequired() {
        assertThatThrownBy(() -> handler.execute(workbook, props("range", "A2:A4")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\"format\" is required");
    }

    @Test
    @DisplayName("decimals outside 0-10 is a mistake worth naming rather than clamping")
    void decimalsAreBounded() {
        assertThatThrownBy(() -> handler.execute(workbook,
                props("range", "A2:A4", "format", "currency", "decimals", 11)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0 and 10");
    }

    @Test
    @DisplayName("a whole-column range is refused — it would create a million rows")
    void aWholeColumnIsRefused() {
        assertThatThrownBy(() -> handler.execute(workbook, props("range", "A:A", "format", "currency")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bounded range");
    }

    // ── The report ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("text in the range is reported: no number format can reach a string")
    void textCellsAreReported() throws Exception {
        sheet.getRow(2).getCell(0).setCellValue("1,234");

        String detail = handler.execute(workbook, props("range", "A2:A4", "format", "currency"));

        assertThat(detail)
                .contains("1 cell holds text")
                .contains("TRANSFORM_COLUMN");
    }

    @Test
    @DisplayName("formatting text AS text is the action working, so it says nothing")
    void theTextFormatDoesNotComplainAboutText() throws Exception {
        sheet.getRow(2).getCell(0).setCellValue("007");

        assertThat(handler.execute(workbook, props("range", "A2:A4", "format", "text"))).isNull();
    }

    // ── What it must not destroy ──────────────────────────────────────────────

    @Test
    @DisplayName("the colour and weight a cell already had survive the format")
    void existingFormattingSurvives() throws Exception {
        XSSFCellStyle painted = workbook.createCellStyle();
        painted.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        painted.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        var bold = workbook.createFont();
        bold.setBold(true);
        painted.setFont(bold);
        sheet.getRow(1).getCell(0).setCellStyle(painted);

        handler.execute(workbook, props("range", "A2:A4", "format", "currency"));

        XSSFCellStyle after = sheet.getRow(1).getCell(0).getCellStyle();
        assertThat(after.getDataFormatString()).isEqualTo("\"$\"#,##0.00");
        assertThat(after.getFillPattern()).isEqualTo(FillPatternType.SOLID_FOREGROUND);
        assertThat(after.getFont().getBold()).isTrue();
    }

    @Test
    @DisplayName("one style per variant, however many cells share it")
    void stylesAreSharedAcrossTheRange() throws Exception {
        int before = workbook.getNumCellStyles();

        handler.execute(workbook, props("range", "A2:A4", "format", "currency"));

        assertThat(workbook.getNumCellStyles() - before)
                .as("three identically styled cells getting the same edit need one new style")
                .isEqualTo(1);
    }

    // ── describe() ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the plan card names the format the user asked for, not its Excel pattern")
    void describeReadsAsTheNamedFormat() {
        Map<String, Object> properties = props("range", "C2:C500", "format", "currency");

        assertThat(handler.describe(properties, StepTense.IMPERATIVE))
                .isEqualTo("Show C2:C500 as currency");
        assertThat(handler.describe(properties, StepTense.PAST))
                .isEqualTo("Showed C2:C500 as currency");
    }

    @Test
    @DisplayName("a literal pattern is quoted rather than pretending to be a sentence")
    void describeQuotesALiteralPattern() {
        assertThat(handler.describe(props("range", "C2:C9", "format", "#,##0.000"), StepTense.PAST))
                .contains("the format").contains("#,##0.000");
    }

    @Test
    @DisplayName("describe survives the keys a model gets wrong")
    void describeNeverThrows() {
        assertThat(handler.describe(Map.of(), StepTense.IMPERATIVE)).isNotBlank();
        assertThat(handler.describe(props("range", 42, "format", true), StepTense.PAST)).isNotBlank();
    }
}
