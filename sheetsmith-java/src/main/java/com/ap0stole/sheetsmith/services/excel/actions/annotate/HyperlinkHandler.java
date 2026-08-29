package com.ap0stole.sheetsmith.services.excel.actions.annotate;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.annotate.HyperlinkConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.CellStyles;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.FontUnderline;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFHyperlink;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Makes a cell clickable — a web address, an email, a file, or another sheet in the same workbook.
 * <p>
 * Two shapes, because a column of addresses typed as plain text is the common case and issuing one
 * step per row would bury the plan: {@code cell} plus {@code address} makes a single link, while a
 * {@code range} on its own turns each cell's own text into a link to itself.
 * <p>
 * Excel styles a link blue and underlined when you insert one, and POI does not — a link that looks
 * like plain text reads as a step that did nothing. The styling therefore goes through
 * {@link CellStyles}, which keeps every other facet the cell had; the font is rebuilt from the
 * cell's own rather than replaced, so a bold heading stays bold when it becomes a link.
 */
@Slf4j
@Component
public class HyperlinkHandler implements ActionHandler {

    /** The scheme that turns a link into an email address rather than a page. */
    private static final String MAILTO = "mailto:";

    /** Excel's own hyperlink blue. */
    private static final String LINK_BLUE = "#0563C1";

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getType() {
        return "HYPERLINK";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws Exception {
        HyperlinkConfig cfg = mapper.convertValue(properties, HyperlinkConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        CreationHelper helper = workbook.getCreationHelper();

        boolean linkifying = notBlank(cfg.getRange()) && !notBlank(cfg.getAddress());
        CellRangeAddress area = CellStyles.area(
                notBlank(cfg.getCell()) ? cfg.getCell() : cfg.getRange(),
                notBlank(cfg.getCell()) ? "cell" : "range");

        if (!linkifying && !notBlank(cfg.getAddress())) {
            throw new IllegalArgumentException("A link needs somewhere to go — give \"address\" with"
                    + " \"cell\", or a \"range\" whose cells already hold the addresses.");
        }

        Set<Long> touched = new HashSet<>();
        int skipped = 0;
        for (int r = area.getFirstRow(); r <= area.getLastRow(); r++) {
            XSSFRow row = sheet.getRow(r);
            for (int c = area.getFirstColumn(); c <= area.getLastColumn(); c++) {
                XSSFCell cell = row == null ? null : row.getCell(c);
                String address = linkifying ? textOf(cell) : cfg.getAddress().trim();
                if (!notBlank(address)) {
                    skipped++;
                    continue;
                }
                if (cell == null) {
                    if (row == null) {
                        row = sheet.createRow(r);
                    }
                    cell = row.createCell(c);
                }
                attach(helper, cell, address, linkifying ? null : cfg.getText(), cfg.getLinkType());
                touched.add(at(r, c));
            }
        }

        if (!touched.isEmpty()) {
            style(workbook, sheet, area, touched);
        }

        log.info("HYPERLINK linked {} cell(s) in {} on '{}'",
                touched.size(), area.formatAsString(), sheet.getSheetName());

        if (touched.isEmpty()) {
            return "no cell in the range held an address, so nothing was linked";
        }
        return skipped == 0 ? null
                : skipped + (skipped == 1 ? " cell held no address and was" : " cells held no address and were")
                + " left alone";
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String cell = ActionDescriptions.range(properties, "cell");
        String range = ActionDescriptions.range(properties, "range");
        String address = ActionDescriptions.text(properties, "address");
        String text = ActionDescriptions.text(properties, "text");

        if (address == null && range != null) {
            return ActionDescriptions.verb(tense, "Make", "Made") + " the addresses in " + range
                    + " clickable" + ActionDescriptions.sheetSuffix(properties);
        }
        String where = cell != null ? cell : range != null ? range : "the cell";
        return ActionDescriptions.verb(tense, "Link", "Linked") + " " + where + " to "
                + (address == null ? "an address" : address)
                + (text == null ? "" : ", showing " + ActionDescriptions.quoted(text))
                + ActionDescriptions.sheetSuffix(properties);
    }

    private void attach(CreationHelper helper, XSSFCell cell, String address, String text, String linkType) {
        HyperlinkType type = type(linkType, address);
        XSSFHyperlink link = (XSSFHyperlink) helper.createHyperlink(type);
        link.setAddress(stored(type, address));
        cell.setHyperlink(link);

        // A link on a cell showing nothing is a link nobody can click; the address is the honest
        // default caption, and an explicit one wins over it.
        if (notBlank(text)) {
            cell.setCellValue(text.trim());
        } else if (cell.getCellType() == CellType.BLANK) {
            cell.setCellValue(address);
        }
    }

    /**
     * The kind of link, guessed from the address when it was not named. Guessing beats refusing:
     * the difference between a web address and an email is obvious from the text, and a model that
     * has to name the type as well gets it wrong more often than the guess does.
     */
    private HyperlinkType type(String named, String address) {
        String keyword = CellStyles.keyword(named);
        if (keyword != null) {
            return switch (keyword) {
                case "url", "web", "http", "link" -> HyperlinkType.URL;
                case "email", "mail", "mailto" -> HyperlinkType.EMAIL;
                case "file", "path" -> HyperlinkType.FILE;
                case "sheet", "document", "internal" -> HyperlinkType.DOCUMENT;
                default -> throw new IllegalArgumentException("Unknown \"linkType\" \"" + named
                        + "\" — use url, email, file or sheet.");
            };
        }
        String lower = address.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("www.")) {
            return HyperlinkType.URL;
        }
        if (lower.startsWith(MAILTO) || (lower.contains("@") && !lower.contains("/"))) {
            return HyperlinkType.EMAIL;
        }
        if (lower.startsWith("#") || lower.matches("^'?[^/\\\\]+'?![a-z]+\\d+$")) {
            return HyperlinkType.DOCUMENT;
        }
        return HyperlinkType.FILE;
    }

    /** What actually goes in the file: a bare address is not clickable without its scheme. */
    private String stored(HyperlinkType type, String address) {
        String lower = address.toLowerCase(Locale.ROOT);
        if (type == HyperlinkType.URL && lower.startsWith("www.")) {
            return "https://" + address;
        }
        if (type == HyperlinkType.EMAIL && !lower.startsWith(MAILTO)) {
            return MAILTO + address;
        }
        if (type == HyperlinkType.DOCUMENT && address.startsWith("#")) {
            return address.substring(1);
        }
        return address;
    }

    /**
     * Blue and underlined, rebuilt from whatever font the cell already had — and only on the cells
     * that actually became links. A null key is how {@link CellStyles} is told a cell is outside the
     * edit; without it, styling a column of 500 addresses would create and style the 490 empty cells
     * between them.
     */
    private void style(XSSFWorkbook workbook, XSSFSheet sheet, CellRangeAddress area, Set<Long> linked) {
        CellStyles.apply(workbook, sheet, area, new CellStyles.StyleEdit() {
            @Override
            public String key(int row, int column) {
                return linked.contains(at(row, column)) ? "link" : null;
            }

            @Override
            public void apply(XSSFCellStyle style, int row, int column) {
                XSSFFont base = style.getFont();
                XSSFFont linked = workbook.createFont();
                linked.setFontName(base.getFontName());
                linked.setFontHeightInPoints(base.getFontHeightInPoints());
                linked.setBold(base.getBold());
                linked.setItalic(base.getItalic());
                linked.setUnderline(FontUnderline.SINGLE);
                linked.setColor(CellStyles.color(LINK_BLUE, "linkColor"));
                style.setFont(linked);
            }
        });
    }

    /** Row and column as one value, so the linked cells can be looked up while styling. */
    private static long at(int row, int column) {
        return ((long) row << 20) | column;
    }

    private String textOf(XSSFCell cell) {
        if (cell == null || cell.getCellType() != CellType.STRING) {
            return null;
        }
        return cell.getStringCellValue().trim();
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
