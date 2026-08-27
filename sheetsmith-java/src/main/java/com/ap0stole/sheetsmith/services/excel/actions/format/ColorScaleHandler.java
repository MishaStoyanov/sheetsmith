package com.ap0stole.sheetsmith.services.excel.actions.format;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.format.ColorScaleConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.CellStyles;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Color;
import org.apache.poi.ss.usermodel.ColorScaleFormatting;
import org.apache.poi.ss.usermodel.ConditionalFormattingRule;
import org.apache.poi.ss.usermodel.ConditionalFormattingThreshold;
import org.apache.poi.ss.usermodel.ConditionalFormattingThreshold.RangeType;
import org.apache.poi.ss.usermodel.SheetConditionalFormatting;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shades a range so each cell's colour is its value's place between the smallest and the largest —
 * the answer to "show me where the big numbers are" that CONDITIONAL_FORMATTING cannot give, because
 * a threshold rule needs a number nobody has worked out yet and paints every cell past it the same.
 * <p>
 * The scale is anchored to the range's own minimum and maximum rather than to typed-in bounds, so it
 * keeps meaning as the data changes; the middle stop sits at the median (the 50th percentile), which
 * is what makes an outlier read as an outlier instead of dragging every other cell to one end.
 */
@Slf4j
@Component
public class ColorScaleHandler implements ActionHandler {

    /** Low, middle, high — from the palette the prompt hands the model, so the card names them back. */
    private static final String DEFAULT_LOW = "#FECACA";
    private static final String DEFAULT_MID = "#FEF08A";
    private static final String DEFAULT_HIGH = "#BBF7D0";

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getType() {
        return "COLOR_SCALE";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws Exception {
        ColorScaleConfig cfg = mapper.convertValue(properties, ColorScaleConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        CellRangeAddress area = CellStyles.area(cfg.getRange(), "range");
        List<XSSFColor> stops = stops(cfg);

        SheetConditionalFormatting scf = sheet.getSheetConditionalFormatting();
        ConditionalFormattingRule rule = scf.createConditionalFormattingColorScaleRule();
        ColorScaleFormatting scale = rule.getColorScaleFormatting();
        scale.setNumControlPoints(stops.size());

        ConditionalFormattingThreshold[] thresholds = scale.getThresholds();
        thresholds[0].setRangeType(RangeType.MIN);
        if (stops.size() == 3) {
            thresholds[1].setRangeType(RangeType.PERCENTILE);
            thresholds[1].setValue(50d);
        }
        thresholds[stops.size() - 1].setRangeType(RangeType.MAX);
        scale.setThresholds(thresholds);
        scale.setColors(stops.toArray(Color[]::new));

        scf.addConditionalFormatting(new CellRangeAddress[]{area}, rule);

        log.info("COLOR_SCALE shaded {} on '{}' across {} colours",
                area.formatAsString(), sheet.getSheetName(), stops.size());
        return ValueScale.detail(ValueScale.coverage(sheet, area));
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String range = ActionDescriptions.range(properties, "range");
        String minColor = ActionDescriptions.text(properties, "minColor");
        String midColor = ActionDescriptions.text(properties, "midColor");
        String maxColor = ActionDescriptions.text(properties, "maxColor");

        // The card has to name every stop the rule will actually use, and which stops those are is
        // the same all-or-nothing question execute() answers — a middle colour the user never sees
        // on the card is a middle colour they cannot review.
        boolean chosen = minColor != null || midColor != null || maxColor != null;
        String low = ActionDescriptions.colorWords(minColor, DEFAULT_LOW);
        String high = ActionDescriptions.colorWords(maxColor, DEFAULT_HIGH);
        String mid = chosen && midColor == null ? null : ActionDescriptions.colorWords(midColor, DEFAULT_MID);

        String stops = mid == null
                ? low + " for the lowest through " + high + " for the highest"
                : low + " for the lowest, " + mid + " in the middle, " + high + " for the highest";

        return ActionDescriptions.verb(tense, "Shade", "Shaded") + " "
                + (range == null ? "the cells" : range) + " by value, " + stops
                + ActionDescriptions.sheetSuffix(properties);
    }

    /**
     * Two colours or three, decided by what was asked for: naming a middle colour is the only way to
     * ask for a middle stop, and naming none at all takes the red-yellow-green default whole rather
     * than mixing a chosen colour with a default one — a scale whose ends disagree says nothing.
     */
    private List<XSSFColor> stops(ColorScaleConfig cfg) {
        boolean chosen = notBlank(cfg.getMinColor()) || notBlank(cfg.getMidColor()) || notBlank(cfg.getMaxColor());
        if (!chosen) {
            return List.of(CellStyles.color(DEFAULT_LOW, "minColor"), CellStyles.color(DEFAULT_MID, "midColor"),
                    CellStyles.color(DEFAULT_HIGH, "maxColor"));
        }
        if (!notBlank(cfg.getMinColor()) || !notBlank(cfg.getMaxColor())) {
            throw new IllegalArgumentException("A colour scale needs both ends — give \"minColor\""
                    + " and \"maxColor\" (and \"midColor\" for a three-colour scale), or none of them"
                    + " for the red-to-green default.");
        }
        List<XSSFColor> stops = new ArrayList<>(3);
        stops.add(CellStyles.color(cfg.getMinColor(), "minColor"));
        if (notBlank(cfg.getMidColor())) {
            stops.add(CellStyles.color(cfg.getMidColor(), "midColor"));
        }
        stops.add(CellStyles.color(cfg.getMaxColor(), "maxColor"));
        return stops;
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
