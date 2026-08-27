package com.ap0stole.sheetsmith.services.excel;

import com.ap0stole.sheetsmith.services.excel.actions.format.AlignCellsHandler;
import com.ap0stole.sheetsmith.services.excel.actions.format.NumberFormatHandler;
import com.ap0stole.sheetsmith.services.excel.actions.format.SetBordersHandler;
import com.ap0stole.sheetsmith.services.excel.model.format.StyleConfig;
import com.ap0stole.sheetsmith.services.excel.actions.format.StyleHandler;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
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

/**
 * The rule the four styling actions share: a style is <em>edited</em>, never replaced.
 * <p>
 * This is the regression the batch exists to prevent. Each handler's own test proves it can set its
 * facet; only here is it proved that setting one does not silently clear the others — which is what
 * the old FORMAT_CELLS did, and what would have made a plan of "format the numbers, then colour the
 * header" quietly undo its own first step.
 */
class CellStylesTest {

    private XSSFWorkbook workbook;
    private XSSFSheet sheet;

    @BeforeEach
    void setUp() {
        workbook = new XSSFWorkbook();
        sheet = workbook.createSheet("Worksheet");
        for (int r = 0; r < 3; r++) {
            var row = sheet.createRow(r);
            for (int c = 0; c < 3; c++) {
                row.createCell(c).setCellValue(100.5 * (r + 1));
            }
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

    @Test
    @DisplayName("four styling steps over one range each keep what the others did, in any order")
    void theStylingActionsCompose() throws Exception {
        new NumberFormatHandler().execute(workbook, props("range", "A1:C3", "format", "currency"));
        new SetBordersHandler().execute(workbook, props("range", "A1:C3", "sides", "outline", "style", "medium"));
        new AlignCellsHandler().execute(workbook, props("range", "A1:C3", "horizontal", "right"));

        StyleConfig colours = new StyleConfig();
        colours.setRange("A1:C3");
        colours.setBackgroundColor("#FEF08A");
        colours.setBold(true);
        new StyleHandler().execute(workbook, colours);

        XSSFCellStyle corner = sheet.getRow(0).getCell(0).getCellStyle();
        assertThat(corner.getDataFormatString()).isEqualTo("\"$\"#,##0.00");
        assertThat(corner.getBorderTop()).isEqualTo(BorderStyle.MEDIUM);
        assertThat(corner.getAlignment()).isEqualTo(HorizontalAlignment.RIGHT);
        assertThat(corner.getFillPattern()).isEqualTo(FillPatternType.SOLID_FOREGROUND);
        assertThat(corner.getFont().getBold()).isTrue();
    }

    @Test
    @DisplayName("colouring no longer strips the number format — the bug the shared helper exists for")
    void colouringKeepsTheNumberFormat() throws Exception {
        new NumberFormatHandler().execute(workbook, props("range", "A1:C3", "format", "percent"));

        StyleConfig colours = new StyleConfig();
        colours.setRange("A1:C3");
        colours.setFontColor("#FFFFFF");
        new StyleHandler().execute(workbook, colours);

        assertThat(sheet.getRow(1).getCell(1).getCellStyle().getDataFormatString()).isEqualTo("0%");
    }

    @Test
    @DisplayName("bolding a header keeps the typeface and size someone chose")
    void boldingKeepsTheRestOfTheFont() throws Exception {
        XSSFCellStyle chosen = workbook.createCellStyle();
        var font = workbook.createFont();
        font.setFontName("Georgia");
        font.setFontHeightInPoints((short) 14);
        font.setItalic(true);
        chosen.setFont(font);
        sheet.getRow(0).getCell(0).setCellStyle(chosen);

        StyleConfig colours = new StyleConfig();
        colours.setRange("A1:A1");
        colours.setBold(true);
        new StyleHandler().execute(workbook, colours);

        var after = sheet.getRow(0).getCell(0).getCellStyle().getFont();
        assertThat(after.getBold()).isTrue();
        assertThat(after.getFontName()).isEqualTo("Georgia");
        assertThat(after.getFontHeightInPoints()).isEqualTo((short) 14);
        assertThat(after.getItalic()).isTrue();
    }

    @Test
    @DisplayName("a bold header stays bold when it is only being coloured")
    void anUnmentionedFacetIsNotReset() throws Exception {
        StyleConfig bolding = new StyleConfig();
        bolding.setRange("A1:C1");
        bolding.setBold(true);
        new StyleHandler().execute(workbook, bolding);

        StyleConfig colouring = new StyleConfig();
        colouring.setRange("A1:C1");
        colouring.setBackgroundColor("#1E3A8A");
        new StyleHandler().execute(workbook, colouring);

        assertThat(sheet.getRow(0).getCell(0).getCellStyle().getFont().getBold())
                .as("\"not mentioned\" is not \"asked to be off\"")
                .isTrue();
    }

    @Test
    @DisplayName("identical cells getting an identical edit share one new style")
    void stylesAreCached() throws Exception {
        int before = workbook.getNumCellStyles();

        new AlignCellsHandler().execute(workbook, props("range", "A1:C3", "horizontal", "center"));

        assertThat(workbook.getNumCellStyles() - before).isEqualTo(1);
    }

    @Test
    @DisplayName("a range past the cell ceiling is refused rather than materialised")
    void theCellCeilingHolds() {
        assertThatThrownBy(() -> CellStyles.area("A1:Z100000", "range"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit is " + CellStyles.MAX_CELLS);
    }

    @Test
    @DisplayName("a whole column or row is refused — POI resolves it to -1 and fails much later")
    void wholeColumnsAndRowsAreRefused() {
        assertThatThrownBy(() -> CellStyles.area("A:A", "range"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole column");
        assertThatThrownBy(() -> CellStyles.area("1:1", "range"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole row");
    }

    @Test
    @DisplayName("a sheet-qualified, dollar-signed range is the same range")
    void aQualifiedRangeIsUnderstood() {
        assertThat(CellStyles.area("Sales!$A$1:$C$3", "range").formatAsString()).isEqualTo("A1:C3");
    }

    @Test
    @DisplayName("styling reaches cells that do not exist yet — a border round an empty box is a real request")
    void missingCellsAreCreated() throws Exception {
        new SetBordersHandler().execute(workbook, props("range", "E5:F6", "sides", "outline"));

        assertThat(sheet.getRow(4).getCell(4).getCellStyle().getBorderTop()).isEqualTo(BorderStyle.THIN);
    }
}
