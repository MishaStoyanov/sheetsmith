package com.ap0stole.sheetsmith.services.excel.actions.view;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.view.PageSetupConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.CellStyles;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How the sheet comes out of a printer or a PDF: which way round the page is, how much is squeezed
 * onto it, which block is printed at all, and which heading rows repeat at the top of every page.
 * <p>
 * The trap is fit-to-page. POI will happily write {@code fitToWidth} while Excel ignores it, because
 * the attribute only takes effect alongside the sheet's own fit-to-page flag — so both are set here,
 * always together. The other half of the same trap is the default height: asking for "one page wide"
 * almost never means "one page tall", and POI's default of 1 would shrink a 500-row sheet to
 * illegibility, so an unnamed height becomes 0, Excel's "as many pages as it takes".
 * <p>
 * Print area and repeating rows are not sheet properties at all — Excel stores them as defined names
 * in the workbook, which is why they are set through the workbook and why the round-trip test checks
 * them in a reopened file rather than in the object graph.
 */
@Slf4j
@Component
public class PageSetupHandler implements ActionHandler {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getType() {
        return "PAGE_SETUP";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws Exception {
        PageSetupConfig cfg = mapper.convertValue(properties, PageSetupConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        int sheetIndex = workbook.getSheetIndex(sheet);
        PrintSetup print = sheet.getPrintSetup();
        List<String> applied = new ArrayList<>();

        String orientation = CellStyles.keyword(cfg.getOrientation());
        if (orientation != null) {
            print.setLandscape(landscape(orientation, cfg.getOrientation()));
            applied.add(orientation);
        }

        if (cfg.getFitToWidth() != null || cfg.getFitToHeight() != null) {
            sheet.setFitToPage(true);
            sheet.setAutobreaks(true);
            print.setFitWidth(pages(cfg.getFitToWidth(), "fitToWidth", 1));
            print.setFitHeight(pages(cfg.getFitToHeight(), "fitToHeight", 0));
            applied.add("fit to page");
        }

        if (notBlank(cfg.getPaperSize())) {
            print.setPaperSize(paperSize(cfg.getPaperSize()));
            applied.add("paper size");
        }

        if (notBlank(cfg.getPrintArea())) {
            // Named through the workbook rather than the sheet: it is a defined name, not a property.
            workbook.setPrintArea(sheetIndex, reference(cfg.getPrintArea()));
            applied.add("print area");
        }

        if (notBlank(cfg.getRepeatHeaderRows())) {
            sheet.setRepeatingRows(rowRange(cfg.getRepeatHeaderRows()));
            applied.add("repeating rows");
        }

        if (notBlank(cfg.getRepeatHeaderColumns())) {
            sheet.setRepeatingColumns(columnRange(cfg.getRepeatHeaderColumns()));
            applied.add("repeating columns");
        }

        if (cfg.getPrintGridlines() != null) {
            sheet.setPrintGridlines(cfg.getPrintGridlines());
            applied.add("gridlines");
        }

        if (applied.isEmpty()) {
            throw new IllegalArgumentException("PAGE_SETUP was asked for nothing — give at least one"
                    + " of \"orientation\", \"fitToWidth\", \"fitToHeight\", \"printArea\","
                    + " \"repeatHeaderRows\", \"repeatHeaderColumns\", \"paperSize\" or"
                    + " \"printGridlines\".");
        }

        log.info("PAGE_SETUP set {} on '{}'", String.join(", ", applied), sheet.getSheetName());
        return null;
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        List<String> bits = new ArrayList<>();

        String orientation = CellStyles.keyword(ActionDescriptions.text(properties, "orientation"));
        if (orientation != null) {
            bits.add("landscape".equals(orientation) ? "print sideways" : "print upright");
        }

        Integer width = ActionDescriptions.integer(properties, "fitToWidth");
        Integer height = ActionDescriptions.integer(properties, "fitToHeight");
        if (width != null || height != null) {
            bits.add(fit(width, height));
        }

        String area = ActionDescriptions.range(properties, "printArea");
        if (area != null) {
            bits.add("print only " + area);
        }
        String rows = ActionDescriptions.text(properties, "repeatHeaderRows");
        if (rows != null) {
            bits.add("repeat rows " + rows + " on every page");
        }
        String columns = ActionDescriptions.text(properties, "repeatHeaderColumns");
        if (columns != null) {
            bits.add("repeat columns " + columns + " on every page");
        }
        String paper = ActionDescriptions.text(properties, "paperSize");
        if (paper != null) {
            bits.add("print on " + paper.trim().toUpperCase(Locale.ROOT) + " paper");
        }
        if (properties != null && properties.get("printGridlines") != null) {
            bits.add(ActionDescriptions.flag(properties, "printGridlines", false)
                    ? "print the gridlines" : "leave the gridlines off the page");
        }

        String what = bits.isEmpty() ? "the print layout" : String.join(", ", bits);
        return ActionDescriptions.verb(tense, "Set up the page", "Set the page up") + " to " + what
                + ActionDescriptions.sheetSuffix(properties);
    }

    /** "one page wide, as long as it needs" is the ask behind almost every fit-to-page request. */
    private String fit(Integer width, Integer height) {
        String across = width == null || width < 1 ? "1 page" : width + (width == 1 ? " page" : " pages");
        if (height == null || height < 1) {
            return "fit " + across + " across, however many down";
        }
        return "fit onto " + across + " across by " + height + (height == 1 ? " page" : " pages") + " down";
    }

    private boolean landscape(String keyword, String raw) {
        return switch (keyword) {
            case "landscape", "sideways", "horizontal" -> true;
            case "portrait", "upright", "vertical" -> false;
            default -> throw new IllegalArgumentException("Unknown \"orientation\" \"" + raw
                    + "\" — use \"portrait\" or \"landscape\".");
        };
    }

    private short paperSize(String raw) {
        return switch (CellStyles.keyword(raw)) {
            case "a3" -> PrintSetup.A3_PAPERSIZE;
            case "a4" -> PrintSetup.A4_PAPERSIZE;
            case "a5" -> PrintSetup.A5_PAPERSIZE;
            case "letter" -> PrintSetup.LETTER_PAPERSIZE;
            case "legal" -> PrintSetup.LEGAL_PAPERSIZE;
            default -> throw new IllegalArgumentException("Unknown \"paperSize\" \"" + raw
                    + "\" — use A3, A4, A5, letter or legal.");
        };
    }

    /** A page count Excel can store; 0 is its "as many as it takes", which is why it is allowed. */
    private short pages(Integer requested, String key, int fallback) {
        int value = requested == null ? fallback : requested;
        if (value < 0 || value > 32_767) {
            throw new IllegalArgumentException("\"" + key + "\" has to be a page count of 0 or more,"
                    + " where 0 means as many pages as it takes, but was " + requested + ".");
        }
        return (short) value;
    }

    /**
     * Validated but not measured: unlike a styling range a print area costs nothing per cell, and
     * "A:D" — every row of four columns — is a perfectly ordinary thing to print.
     */
    private String reference(String raw) {
        String cleaned = clean(raw);
        try {
            CellRangeAddress.valueOf(cleaned);
        } catch (RuntimeException _) {
            throw new IllegalArgumentException("\"printArea\" names the block to print, like"
                    + " \"A1:D40\", but was \"" + raw + "\".");
        }
        return cleaned;
    }

    private CellRangeAddress rowRange(String raw) {
        String cleaned = clean(raw);
        try {
            return CellRangeAddress.valueOf(cleaned.contains(":") ? cleaned : cleaned + ":" + cleaned);
        } catch (RuntimeException _) {
            throw new IllegalArgumentException("\"repeatHeaderRows\" names rows, like \"1:1\" for the"
                    + " top row or \"1:2\" for the first two, but was \"" + raw + "\".");
        }
    }

    private CellRangeAddress columnRange(String raw) {
        String cleaned = clean(raw);
        try {
            return CellRangeAddress.valueOf(cleaned.contains(":") ? cleaned : cleaned + ":" + cleaned);
        } catch (RuntimeException _) {
            throw new IllegalArgumentException("\"repeatHeaderColumns\" names columns, like \"A:A\""
                    + " for the first one, but was \"" + raw + "\".");
        }
    }

    private String clean(String raw) {
        return raw.substring(raw.lastIndexOf('!') + 1).replace("$", "").trim();
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
