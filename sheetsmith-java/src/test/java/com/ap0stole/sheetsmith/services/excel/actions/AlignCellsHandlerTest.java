package com.ap0stole.sheetsmith.services.excel.actions;

import com.ap0stole.sheetsmith.services.excel.actions.format.AlignCellsHandler;
import com.ap0stole.sheetsmith.services.excel.actions.format.NumberFormatHandler;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
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

class AlignCellsHandlerTest {

    private XSSFWorkbook workbook;
    private XSSFSheet sheet;
    private AlignCellsHandler handler;

    @BeforeEach
    void setUp() {
        workbook = new XSSFWorkbook();
        sheet = workbook.createSheet("Worksheet");
        handler = new AlignCellsHandler();

        for (int r = 0; r < 3; r++) {
            XSSFRow row = sheet.createRow(r);
            for (int c = 0; c < 3; c++) {
                row.createCell(c).setCellValue("r" + r + "c" + c);
            }
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        workbook.close();
    }

    private XSSFCellStyle style(int row, int column) {
        return sheet.getRow(row).getCell(column).getCellStyle();
    }

    private Map<String, Object> props(Object... pairs) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            properties.put((String) pairs[i], pairs[i + 1]);
        }
        return properties;
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("centring a header row, horizontally and vertically")
    void alignsHorizontallyAndVertically() throws Exception {
        assertThat(handler.execute(workbook,
                props("range", "A1:C1", "horizontal", "center", "vertical", "middle"))).isNull();

        assertThat(style(0, 0).getAlignment()).isEqualTo(HorizontalAlignment.CENTER);
        assertThat(style(0, 0).getVerticalAlignment()).isEqualTo(VerticalAlignment.CENTER);
        assertThat(style(1, 0).getAlignment())
                .as("nothing outside the range moves")
                .isEqualTo(HorizontalAlignment.GENERAL);
    }

    @Test
    @DisplayName("the spellings users actually send — centre, right, top — all resolve")
    void theSpellingsResolve() throws Exception {
        handler.execute(workbook, props("range", "A1:A1", "horizontal", "centre"));
        handler.execute(workbook, props("range", "B1:B1", "horizontal", "RIGHT"));
        handler.execute(workbook, props("range", "C1:C1", "vertical", "top"));

        assertThat(style(0, 0).getAlignment()).isEqualTo(HorizontalAlignment.CENTER);
        assertThat(style(0, 1).getAlignment()).isEqualTo(HorizontalAlignment.RIGHT);
        assertThat(style(0, 2).getVerticalAlignment()).isEqualTo(VerticalAlignment.TOP);
    }

    @Test
    @DisplayName("indent is passed through as Excel's own steps")
    void indentIsApplied() throws Exception {
        handler.execute(workbook, props("range", "A1:A3", "indent", 3));

        assertThat(style(0, 0).getIndention()).isEqualTo((short) 3);
    }

    // ── Wrapping, and the row height that would hide it ───────────────────────

    @Test
    @DisplayName("wrapping a row whose height was pinned returns that height to automatic")
    void wrappingFreesAFixedRowHeight() throws Exception {
        sheet.getRow(0).setHeight((short) 300);

        String detail = handler.execute(workbook, props("range", "A1:C1", "wrapText", true));

        assertThat(style(0, 0).getWrapText()).isTrue();
        assertThat(sheet.getRow(0).getCTRow().getCustomHeight())
                .as("a pinned height clips wrapped text instead of growing to fit it")
                .isFalse();
        assertThat(detail).contains("1 row had a fixed height");
    }

    @Test
    @DisplayName("a row that was already automatic is left alone, and says nothing")
    void anAutomaticRowIsNotTouched() throws Exception {
        assertThat(handler.execute(workbook, props("range", "A1:C1", "wrapText", true))).isNull();

        assertThat(style(0, 0).getWrapText()).isTrue();
    }

    @Test
    @DisplayName("turning wrapping off is not a reason to touch row heights")
    void unwrappingLeavesHeightsAlone() throws Exception {
        sheet.getRow(0).setHeight((short) 300);

        assertThat(handler.execute(workbook, props("range", "A1:C1", "wrapText", false))).isNull();

        assertThat(sheet.getRow(0).getHeight()).isEqualTo((short) 300);
    }

    @Test
    @DisplayName("a hidden row stays hidden — it was hidden on purpose")
    void aHiddenRowIsNotReopened() throws Exception {
        sheet.getRow(1).setZeroHeight(true);

        handler.execute(workbook, props("range", "A1:C3", "wrapText", true));

        assertThat(sheet.getRow(1).getZeroHeight()).isTrue();
    }

    // ── What it refuses ───────────────────────────────────────────────────────

    @Test
    @DisplayName("an alignment step that names nothing would do nothing, so it is an error")
    void namingNothingIsRefused() {
        var properties = props("range", "A1:C1");
        assertThatThrownBy(() -> handler.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nothing to align");
    }

    @Test
    @DisplayName("an unknown alignment is named rather than quietly ignored")
    void anUnknownAlignmentIsRefused() {
        var properties = props("range", "A1:C1", "horizontal", "sideways");
        assertThatThrownBy(() -> handler.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sideways");
    }

    @Test
    @DisplayName("indent outside Excel's own range is refused")
    void indentIsBounded() {
        var properties = props("range", "A1:C1", "indent", 20);
        assertThatThrownBy(() -> handler.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0 and 15");
    }

    // ── What it must not destroy ──────────────────────────────────────────────

    @Test
    @DisplayName("a number format applied earlier survives being centred")
    void anExistingNumberFormatSurvives() throws Exception {
        new NumberFormatHandler().execute(workbook, props("range", "A1:C1", "format", "currency"));

        handler.execute(workbook, props("range", "A1:C1", "horizontal", "center"));

        assertThat(style(0, 0).getDataFormatString()).isEqualTo("\"$\"#,##0.00");
        assertThat(style(0, 0).getAlignment()).isEqualTo(HorizontalAlignment.CENTER);
    }

    // ── describe() ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the plan card reads as what the user will see")
    void describeReadsAsASentence() {
        assertThat(handler.describe(props("range", "A1:E1", "horizontal", "center"), StepTense.IMPERATIVE))
                .isEqualTo("Align A1:E1 — centred");
        assertThat(handler.describe(props("range", "A1:E1", "horizontal", "center", "wrapText", true),
                StepTense.PAST))
                .isEqualTo("Aligned A1:E1 — centred and wrapped");
    }

    @Test
    @DisplayName("wrapping asked to be off reads as unwrapped, not as wrapped")
    void describeSeparatesWrapFromUnwrap() {
        assertThat(handler.describe(props("range", "A1:E1", "wrapText", false), StepTense.PAST))
                .contains("unwrapped");
    }

    @Test
    @DisplayName("describe survives the keys a model gets wrong")
    void describeNeverThrows() {
        assertThat(handler.describe(Map.of(), StepTense.IMPERATIVE)).isNotBlank();
        assertThat(handler.describe(props("range", 7, "horizontal", 3), StepTense.PAST)).isNotBlank();
    }
}
