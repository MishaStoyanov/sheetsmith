package com.ap0stole.sheetsmith.services.excel.actions;

import com.ap0stole.sheetsmith.services.excel.actions.format.ColorScaleHandler;
import com.ap0stole.sheetsmith.services.excel.actions.format.DataBarsHandler;
import org.apache.poi.ss.usermodel.ColorScaleFormatting;
import org.apache.poi.ss.usermodel.ConditionType;
import org.apache.poi.ss.usermodel.ConditionalFormattingRule;
import org.apache.poi.ss.usermodel.ConditionalFormattingThreshold.RangeType;
import org.apache.poi.ss.usermodel.DataBarFormatting;
import org.apache.poi.ss.usermodel.SheetConditionalFormatting;
import org.apache.poi.xssf.usermodel.XSSFColor;
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
 * The two rules that paint a range by magnitude. Both are anchored to the range's own minimum and
 * maximum, and both are silent about cells holding no number — which is what the reported detail
 * exists to say out loud.
 */
class ColorScaleAndDataBarsTest {

    private XSSFWorkbook workbook;
    private XSSFSheet sheet;
    private final ColorScaleHandler colorScale = new ColorScaleHandler();
    private final DataBarsHandler dataBars = new DataBarsHandler();

    @BeforeEach
    void setUp() {
        workbook = new XSSFWorkbook();
        sheet = workbook.createSheet("Data");
        sheet.createRow(0).createCell(0).setCellValue("Amount");
        for (int r = 1; r <= 5; r++) {
            XSSFRow row = sheet.createRow(r);
            row.createCell(0).setCellValue(r * 100.0);
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

    private ConditionalFormattingRule onlyRule() {
        SheetConditionalFormatting scf = sheet.getSheetConditionalFormatting();
        assertThat(scf.getNumConditionalFormattings()).isEqualTo(1);
        return scf.getConditionalFormattingAt(0).getRule(0);
    }

    private String hexOf(org.apache.poi.ss.usermodel.Color color) {
        return ((XSSFColor) color).getARGBHex();
    }

    // ── COLOR_SCALE ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("with no colours asked for, three stops run red through yellow to green")
    void defaultsToAThreeColourScale() throws Exception {
        String detail = colorScale.execute(workbook, props("range", "A2:A6"));

        ConditionalFormattingRule rule = onlyRule();
        assertThat(rule.getConditionType()).isEqualTo(ConditionType.COLOR_SCALE);

        ColorScaleFormatting scale = rule.getColorScaleFormatting();
        assertThat(scale.getNumControlPoints()).isEqualTo(3);
        assertThat(scale.getColors()).extracting(this::hexOf)
                .containsExactly("FFFECACA", "FFFEF08A", "FFBBF7D0");
        assertThat(detail).as("every cell in the range is a number").isNull();
    }

    @Test
    @DisplayName("the ends anchor to the range's own min and max, the middle to the median")
    void anchorsToTheDataRatherThanToTypedBounds() throws Exception {
        colorScale.execute(workbook, props("range", "A2:A6"));

        var thresholds = onlyRule().getColorScaleFormatting().getThresholds();
        assertThat(thresholds[0].getRangeType()).isEqualTo(RangeType.MIN);
        assertThat(thresholds[1].getRangeType()).isEqualTo(RangeType.PERCENTILE);
        assertThat(thresholds[1].getValue()).isEqualTo(50d);
        assertThat(thresholds[2].getRangeType()).isEqualTo(RangeType.MAX);
    }

    @Test
    @DisplayName("naming both ends and no middle makes a two-colour scale")
    void twoColoursWhenNoMiddleIsNamed() throws Exception {
        colorScale.execute(workbook, props(
                "range", "A2:A6", "minColor", "#FFFFFF", "maxColor", "#1E3A8A"));

        ColorScaleFormatting scale = onlyRule().getColorScaleFormatting();
        assertThat(scale.getNumControlPoints()).isEqualTo(2);
        assertThat(scale.getColors()).extracting(this::hexOf)
                .containsExactly("FFFFFFFF", "FF1E3A8A");
        assertThat(scale.getThresholds()[1].getRangeType()).isEqualTo(RangeType.MAX);
    }

    @Test
    @DisplayName("one end without the other is refused rather than half-defaulted")
    void refusesAHalfNamedScale() {
        assertThatThrownBy(() -> colorScale.execute(workbook, props(
                "range", "A2:A6", "maxColor", "#15803D")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minColor");
    }

    @Test
    @DisplayName("a colour that is not a hex value names the key that was wrong")
    void refusesANonHexColour() {
        assertThatThrownBy(() -> colorScale.execute(workbook, props(
                "range", "A2:A6", "minColor", "reddish", "maxColor", "#15803D")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minColor");
    }

    @Test
    @DisplayName("a whole-column range is refused, as everywhere else that walks cells")
    void refusesAWholeColumnRange() {
        assertThatThrownBy(() -> colorScale.execute(workbook, props("range", "A:A")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bounded range");
    }

    @Test
    @DisplayName("text in the range is counted and reported, not painted over in silence")
    void reportsCellsHoldingText() throws Exception {
        sheet.getRow(3).getCell(0).setCellValue("n/a");

        String detail = colorScale.execute(workbook, props("range", "A2:A6"));

        assertThat(detail).isEqualTo("1 cell holds text rather than a number and stays unpainted");
    }

    @Test
    @DisplayName("a range with no numbers at all says so — the rule would render nothing")
    void reportsARangeThatCanNeverPaint() throws Exception {
        String detail = colorScale.execute(workbook, props("range", "A1:A1"));

        assertThat(detail).contains("no cell in the range holds a number");
    }

    @Test
    @DisplayName("a formula cell counts by its cached result, which is what Excel paints from")
    void countsFormulasByTheirCachedResult() throws Exception {
        XSSFRow row = sheet.getRow(2);
        row.getCell(0).setCellFormula("A2*2");
        row.getCell(0).setCellValue(200.0);

        String detail = colorScale.execute(workbook, props("range", "A2:A6"));

        assertThat(detail).as("a numeric formula is a number like any other").isNull();
    }

    // ── DATA_BARS ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("bars are drawn in the asked-for colour, measured min to max")
    void drawsBarsAnchoredToTheRange() throws Exception {
        String detail = dataBars.execute(workbook, props("range", "A2:A6", "color", "#15803D"));

        ConditionalFormattingRule rule = onlyRule();
        assertThat(rule.getConditionType()).isEqualTo(ConditionType.DATA_BAR);

        DataBarFormatting bar = rule.getDataBarFormatting();
        assertThat(hexOf(bar.getColor())).isEqualTo("FF15803D");
        assertThat(bar.getMinThreshold().getRangeType()).isEqualTo(RangeType.MIN);
        assertThat(bar.getMaxThreshold().getRangeType()).isEqualTo(RangeType.MAX);
        assertThat(detail).isNull();
    }

    @Test
    @DisplayName("the number stays visible unless showValue says otherwise")
    void keepsTheNumberVisibleByDefault() throws Exception {
        dataBars.execute(workbook, props("range", "A2:A6"));

        assertThat(onlyRule().getDataBarFormatting().isIconOnly())
                .as("POI's isIconOnly is Excel's \"show bar only\"")
                .isFalse();
    }

    @Test
    @DisplayName("showValue false leaves the bar alone in the cell")
    void hidesTheNumberWhenAsked() throws Exception {
        dataBars.execute(workbook, props("range", "A2:A6", "showValue", false));

        assertThat(onlyRule().getDataBarFormatting().isIconOnly()).isTrue();
    }

    @Test
    @DisplayName("the default bar colour is used when none is named")
    void defaultsToSkyBlue() throws Exception {
        dataBars.execute(workbook, props("range", "A2:A6"));

        assertThat(hexOf(onlyRule().getDataBarFormatting().getColor())).isEqualTo("FF0EA5E9");
    }

    @Test
    @DisplayName("a missing range is refused with the key named")
    void refusesAMissingRange() {
        assertThatThrownBy(() -> dataBars.execute(workbook, props("color", "#0EA5E9")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("range");
    }

    @Test
    @DisplayName("both rules target a named sheet rather than the first one")
    void honoursSheetName() throws Exception {
        XSSFSheet other = workbook.createSheet("Summary");
        other.createRow(0).createCell(0).setCellValue(42.0);

        dataBars.execute(workbook, props("range", "A1:A1", "sheetName", "Summary"));

        assertThat(sheet.getSheetConditionalFormatting().getNumConditionalFormattings()).isZero();
        assertThat(other.getSheetConditionalFormatting().getNumConditionalFormattings()).isEqualTo(1);
    }
}
