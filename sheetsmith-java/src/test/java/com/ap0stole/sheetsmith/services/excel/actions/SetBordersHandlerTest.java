package com.ap0stole.sheetsmith.services.excel.actions;

import com.ap0stole.sheetsmith.services.excel.actions.format.SetBordersHandler;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import org.apache.poi.ss.usermodel.BorderStyle;
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

class SetBordersHandlerTest {

    private XSSFWorkbook workbook;
    private XSSFSheet sheet;
    private SetBordersHandler handler;

    @BeforeEach
    void setUp() {
        workbook = new XSSFWorkbook();
        sheet = workbook.createSheet("Worksheet");
        handler = new SetBordersHandler();

        // A 3x3 block, so "the range's edge" and "every cell's edge" cannot be confused.
        for (int r = 0; r < 3; r++) {
            var row = sheet.createRow(r);
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

    // ── Which edges the sides come down to ────────────────────────────────────

    @Test
    @DisplayName("no sides means every cell's four edges — the only thing \"borders\" can mean alone")
    void defaultsToEveryEdgeOfEveryCell() throws Exception {
        assertThat(handler.execute(workbook, props("range", "A1:C3"))).isNull();

        XSSFCellStyle middle = style(1, 1);
        assertThat(middle.getBorderTop()).isEqualTo(BorderStyle.THIN);
        assertThat(middle.getBorderBottom()).isEqualTo(BorderStyle.THIN);
        assertThat(middle.getBorderLeft()).isEqualTo(BorderStyle.THIN);
        assertThat(middle.getBorderRight()).isEqualTo(BorderStyle.THIN);
    }

    @Test
    @DisplayName("outline draws the block's perimeter and nothing inside it")
    void outlineTouchesOnlyTheEdgeCells() throws Exception {
        handler.execute(workbook, props("range", "A1:C3", "sides", "outline", "style", "medium"));

        XSSFCellStyle topLeft = style(0, 0);
        assertThat(topLeft.getBorderTop()).isEqualTo(BorderStyle.MEDIUM);
        assertThat(topLeft.getBorderLeft()).isEqualTo(BorderStyle.MEDIUM);
        assertThat(topLeft.getBorderRight()).as("the inside of the block stays open").isEqualTo(BorderStyle.NONE);
        assertThat(topLeft.getBorderBottom()).isEqualTo(BorderStyle.NONE);

        XSSFCellStyle middle = style(1, 1);
        assertThat(middle.getBorderTop()).isEqualTo(BorderStyle.NONE);
        assertThat(middle.getBorderLeft()).isEqualTo(BorderStyle.NONE);
    }

    @Test
    @DisplayName("\"bottom\" over three rows is one line under the block, not three under each row")
    void bottomIsTheRangesBottom() throws Exception {
        handler.execute(workbook, props("range", "A1:C3", "sides", "bottom"));

        assertThat(style(2, 0).getBorderBottom()).isEqualTo(BorderStyle.THIN);
        assertThat(style(0, 0).getBorderBottom()).isEqualTo(BorderStyle.NONE);
        assertThat(style(1, 0).getBorderBottom()).isEqualTo(BorderStyle.NONE);
    }

    @Test
    @DisplayName("sides combine: a boxed header ruled off underneath, in one step")
    void sidesCombine() throws Exception {
        handler.execute(workbook, props("range", "A1:C1", "sides", "outline,bottom"));

        assertThat(style(0, 0).getBorderTop()).isEqualTo(BorderStyle.THIN);
        assertThat(style(0, 0).getBorderLeft()).isEqualTo(BorderStyle.THIN);
        assertThat(style(0, 2).getBorderRight()).isEqualTo(BorderStyle.THIN);
        assertThat(style(0, 1).getBorderBottom()).isEqualTo(BorderStyle.THIN);
    }

    @Test
    @DisplayName("inside draws between the cells and leaves the perimeter alone")
    void insideIsTheComplementOfOutline() throws Exception {
        handler.execute(workbook, props("range", "A1:C3", "sides", "inside"));

        assertThat(style(0, 0).getBorderTop()).isEqualTo(BorderStyle.NONE);
        assertThat(style(0, 0).getBorderBottom()).isEqualTo(BorderStyle.THIN);
        assertThat(style(1, 1).getBorderTop()).isEqualTo(BorderStyle.THIN);
    }

    @Test
    @DisplayName("\"box\" and \"grid\" are the words users reach for, and mean outline and all")
    void aliasesResolve() throws Exception {
        handler.execute(workbook, props("range", "A1:C3", "sides", "box"));

        assertThat(style(0, 0).getBorderTop()).isEqualTo(BorderStyle.THIN);
        assertThat(style(1, 1).getBorderTop()).isEqualTo(BorderStyle.NONE);
    }

    // ── Colour, removal and refusals ──────────────────────────────────────────

    @Test
    @DisplayName("a hex colour reaches the border it was asked for")
    void theColourIsApplied() throws Exception {
        handler.execute(workbook, props("range", "A1:C1", "sides", "bottom", "color", "#DC2626"));

        assertThat(style(0, 0).getBottomBorderXSSFColor().getARGBHex()).endsWith("DC2626");
    }

    @Test
    @DisplayName("removal clears the borders and admits what it cannot reach")
    void removalIsHonestAboutNeighbours() throws Exception {
        handler.execute(workbook, props("range", "A1:C3"));

        String detail = handler.execute(workbook, props("range", "A1:C3", "style", "none"));

        assertThat(style(1, 1).getBorderTop()).isEqualTo(BorderStyle.NONE);
        assertThat(detail).contains("neighbouring cell");
    }

    @Test
    @DisplayName("an unknown side or style is named rather than quietly ignored")
    void unknownValuesAreRefused() {
        var properties = props("range", "A1:C3", "sides", "diagonal");
        assertThatThrownBy(() -> handler.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("diagonal");

        var properties2 = props("range", "A1:C3", "style", "wavy");
        assertThatThrownBy(() -> handler.execute(workbook, properties2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wavy");
    }

    @Test
    @DisplayName("a colour that is not a colour is refused, not dropped")
    void aBadColourIsRefused() {
        var properties = props("range", "A1:C3", "color", "reddish");
        assertThatThrownBy(() -> handler.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hex colour");
    }

    // ── The style table ───────────────────────────────────────────────────────

    @Test
    @DisplayName("an outline over 9 cells needs a handful of styles, not nine")
    void stylesAreSharedPerEdgeCombination() throws Exception {
        int before = workbook.getNumCellStyles();

        handler.execute(workbook, props("range", "A1:C3", "sides", "outline"));

        // Corners, edges and the untouched middle: eight distinct edge combinations at most.
        assertThat(workbook.getNumCellStyles() - before).isLessThanOrEqualTo(8);
    }

    // ── describe() ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the plan card says what will appear, in the tense of the moment")
    void describeReadsAsASentence() {
        assertThat(handler.describe(props("range", "A1:D20"), StepTense.IMPERATIVE))
                .isEqualTo("Draw thin borders around every cell on A1:D20");
        assertThat(handler.describe(props("range", "A1:D20", "sides", "outline", "style", "thick"),
                StepTense.PAST))
                .isEqualTo("Drew a thick border around the outside on A1:D20");
    }

    @Test
    @DisplayName("removing borders reads as removing, not as drawing style \"none\"")
    void describeReadsRemovalAsRemoval() {
        assertThat(handler.describe(props("range", "A1:D20", "style", "none"), StepTense.IMPERATIVE))
                .isEqualTo("Remove the borders from A1:D20");
    }

    @Test
    @DisplayName("describe survives the keys a model gets wrong")
    void describeNeverThrows() {
        assertThat(handler.describe(Map.of(), StepTense.PAST)).isNotBlank();
        assertThat(handler.describe(props("range", "A1:B2", "style", "wavy"), StepTense.PAST)).isNotBlank();
    }
}
