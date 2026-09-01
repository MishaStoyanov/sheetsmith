package com.ap0stole.sheetsmith.services.excel.actions.annotate;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.annotate.HyperlinkConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.CellStyles;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;
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
import java.io.IOException;

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

    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Override
    public String getType() {
        return "HYPERLINK";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws IOException {
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

        Linked linked = link(workbook, sheet, helper, area, cfg, linkifying);

        log.info("HYPERLINK linked {} cell(s) in {} on '{}'",
                linked.cells().size(), area.formatAsString(), sheet.getSheetName());

        return linked.report();
    }

    /** What one pass over the range did: the cells that got a link, and the ones that had nothing. */
    private record Linked(Set<Long> cells, int skipped) {

        /** Null when everything worked, because a step with nothing to say says nothing. */
        String report() {
            if (cells.isEmpty()) {
                return "no cell in the range held an address, so nothing was linked";
            }
            if (skipped == 0) {
                return null;
            }
            return skipped + (skipped == 1 ? " cell held no address and was" : " cells held no address and were")
                    + " left alone";
        }
    }

    /**
     * Walks the range once, attaching a link to every cell that has an address to attach.
     * <p>
     * Two shapes share this loop: an explicit address written into every cell of a range, and a
     * range whose cells already hold the addresses. They differ only in where the address comes
     * from, which is why the flag rather than two nearly identical loops.
     */
    private Linked link(XSSFWorkbook workbook, XSSFSheet sheet, CreationHelper helper,
                        CellRangeAddress area, HyperlinkConfig cfg, boolean linkifying) {
        Set<Long> touched = new HashSet<>();
        int skipped = 0;

        for (int r = area.getFirstRow(); r <= area.getLastRow(); r++) {
            for (int c = area.getFirstColumn(); c <= area.getLastColumn(); c++) {
                if (linkOne(sheet, helper, cfg, linkifying, r, c)) {
                    touched.add(at(r, c));
                } else {
                    skipped++;
                }
            }
        }

        if (!touched.isEmpty()) {
            style(workbook, sheet, area, touched);
        }
        return new Linked(touched, skipped);
    }

    /**
     * One cell: link it if there is an address for it.
     *
     * @return true when a link was attached, false when the cell had nothing to link
     */
    private boolean linkOne(XSSFSheet sheet, CreationHelper helper, HyperlinkConfig cfg,
                            boolean linkifying, int row, int column) {
        XSSFRow sheetRow = sheet.getRow(row);
        XSSFCell cell = sheetRow == null ? null : sheetRow.getCell(column);
        String address = linkifying ? textOf(cell) : cfg.getAddress().trim();
        if (!notBlank(address)) {
            return false;
        }
        if (cell == null) {
            sheetRow = sheetRow == null ? sheet.createRow(row) : sheetRow;
            cell = sheetRow.createCell(column);
        }
        attach(helper, cell, address, linkifying ? null : cfg.getText(), cfg.getLinkType());
        return true;
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
        String where = where(cell, range);
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

    /** Whichever of the two the step named, or a word for "wherever it is" when neither. */
    private static String where(String cell, String range) {
        if (cell != null) {
            return cell;
        }
        return range != null ? range : "the cell";
    }
}
