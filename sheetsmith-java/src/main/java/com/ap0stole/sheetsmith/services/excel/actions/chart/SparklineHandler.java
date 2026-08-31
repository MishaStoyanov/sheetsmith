package com.ap0stole.sheetsmith.services.excel.actions.chart;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.chart.SparklineConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.CellStyles;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlException;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTExtension;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTExtensionList;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTWorksheet;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.io.IOException;

/**
 * Draws a one-cell chart from a row of numbers — the shape of a year of sales inside the cell at the
 * end of the row, where a real chart would need its own space and its own step.
 * <p>
 * <b>This action is hand-written XML, and that is not a shortcut.</b> Sparklines are an Excel 2010
 * extension: they live in the worksheet's {@code extLst} under the x14 namespace, and Apache POI
 * 5.5.1 has no API for them at all — no {@code XSSFSheet} method, and not one generated schema class
 * in {@code poi-ooxml-full} (checked, rather than assumed). So the element is built as text and
 * copied into the extension list with an {@link XmlCursor}, which means nothing validates it on the
 * way out: POI will write whatever it is handed, and a wrong shape reaches the user as Excel offering
 * to repair their spreadsheet.
 * <p>
 * That is why the accompanying verification is not the usual POI round trip alone. The file this
 * writes was opened by Excel itself, re-saved by Excel, and read back — see the note in ARCHITECTURE.md.
 * If the XML here is ever changed, that check has to be run again; a passing Java test only proves
 * the bytes are where we put them.
 */
@Slf4j
@Component
public class SparklineHandler implements ActionHandler {

    /** The two sparkline shapes that are named in the switch, the validation and the card. */
    private static final String COLUMN = "column";
    private static final String STACKED = "stacked";

    /**
     * Excel's identifier for the sparkline extension — <b>and its exact casing is load-bearing</b>.
     * Excel matches this GUID case-sensitively: written as {@code 4FD2} rather than {@code 4fd2} the
     * block is carried through the file untouched and ignored, which looks exactly like success from
     * this side. Measured, not assumed: Excel reports zero sparkline groups for the one and a
     * recognised group for the other.
     */
    static final String SPARKLINE_EXT_URI = "{05C60535-1F16-4fd2-B633-F4F36F0B64E0}";

    private static final String MAIN_NS =
            "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String X14_NS = "http://schemas.microsoft.com/office/spreadsheetml/2009/9/main";
    private static final String XM_NS = "http://schemas.microsoft.com/office/excel/2006/main";

    /** Excel's own default series colour for a new sparkline. */
    private static final String DEFAULT_COLOR = "#376092";

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getType() {
        return "SPARKLINES";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws IOException {
        SparklineConfig cfg = mapper.convertValue(properties, SparklineConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        CellRangeAddress targets = CellStyles.area(cfg.getRange(), "range");
        CellRangeAddress data = CellStyles.area(strip(cfg.getDataRange()), "dataRange");

        if (targets.getFirstColumn() != targets.getLastColumn()
                && targets.getFirstRow() != targets.getLastRow()) {
            throw new IllegalArgumentException("\"range\" holds the sparkline cells and has to be one"
                    + " column or one row, but " + targets.formatAsString() + " is a block.");
        }

        boolean downAColumn = targets.getFirstColumn() == targets.getLastColumn();
        int count = downAColumn
                ? targets.getLastRow() - targets.getFirstRow() + 1
                : targets.getLastColumn() - targets.getFirstColumn() + 1;
        int series = downAColumn
                ? data.getLastRow() - data.getFirstRow() + 1
                : data.getLastColumn() - data.getFirstColumn() + 1;

        if (series != count) {
            throw new IllegalArgumentException("\"dataRange\" has to hold one " + (downAColumn
                    ? "row per sparkline cell, but " + data.formatAsString() + " has " + series
                    + " rows for " + count + " cells."
                    : "column per sparkline cell, but " + data.formatAsString() + " has " + series
                    + " columns for " + count + " cells."));
        }

        String type = type(cfg.getType());
        String colour = colour(cfg.getColor());
        String dataSheet = prefix(cfg.getDataRange(), sheet.getSheetName());

        StringBuilder sparklines = new StringBuilder();
        for (int i = 0; i < count; i++) {
            CellRangeAddress slice = downAColumn
                    ? new CellRangeAddress(data.getFirstRow() + i, data.getFirstRow() + i,
                    data.getFirstColumn(), data.getLastColumn())
                    : new CellRangeAddress(data.getFirstRow(), data.getLastRow(),
                    data.getFirstColumn() + i, data.getFirstColumn() + i);
            CellReference target = downAColumn
                    ? new CellReference(targets.getFirstRow() + i, targets.getFirstColumn())
                    : new CellReference(targets.getFirstRow(), targets.getFirstColumn() + i);

            sparklines.append("<x14:sparkline><xm:f>")
                    .append(escape(dataSheet)).append("!").append(slice.formatAsString())
                    .append("</xm:f><xm:sqref>").append(target.formatAsString())
                    .append("</xm:sqref></x14:sparkline>");
        }

        try {
            addGroup(sheet, group(type, colour, cfg, sparklines.toString()));
        } catch (XmlException e) {
            // The XML is built in this class from values already validated above, so a parse
            // failure is a bug here rather than anything the caller did — but it is checked, and
            // the step contract is IOException, so it is reported as what it is.
            throw new IllegalStateException("Could not build the sparkline group XML", e);
        }

        log.info("SPARKLINES drew {} {} sparkline(s) in {} on '{}'",
                count, type, targets.formatAsString(), sheet.getSheetName());
        return null;
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String range = ActionDescriptions.range(properties, "range");
        String data = ActionDescriptions.text(properties, "dataRange");
        String type = CellStyles.keyword(ActionDescriptions.text(properties, "type"));

        String shape = switch (type == null ? "line" : type) {
            case COLUMN, "bar" -> "bar";
            case "winloss", "win_loss", STACKED -> "win/loss";
            default -> "line";
        };

        return ActionDescriptions.verb(tense, "Draw", "Drew") + " a " + shape
                + " sparkline in each of " + (range == null ? "the cells" : range)
                + (data == null ? "" : " from " + data)
                + ActionDescriptions.sheetSuffix(properties);
    }

    /** The group element, with the colours Excel writes for a new sparkline of this kind. */
    private String group(String type, String colour, SparklineConfig cfg, String sparklines) {
        boolean markers = Boolean.TRUE.equals(cfg.getShowMarkers()) && "line".equals(type);
        return "<x14:sparklineGroups xmlns:x14=\"" + X14_NS + "\" xmlns:xm=\"" + XM_NS + "\">"
                + "<x14:sparklineGroup displayEmptyCellsAs=\"gap\""
                + ("line".equals(type) ? "" : " type=\"" + type + "\"")
                + (markers ? " markers=\"1\"" : "")
                + ">"
                + "<x14:colorSeries rgb=\"" + colour + "\"/>"
                + "<x14:colorNegative rgb=\"FFD00000\"/>"
                + "<x14:colorAxis rgb=\"FF000000\"/>"
                + "<x14:colorMarkers rgb=\"FFD00000\"/>"
                + "<x14:colorFirst rgb=\"FFD00000\"/>"
                + "<x14:colorLast rgb=\"FFD00000\"/>"
                + "<x14:colorHigh rgb=\"FFD00000\"/>"
                + "<x14:colorLow rgb=\"FFD00000\"/>"
                + "<x14:sparklines>" + sparklines + "</x14:sparklines>"
                + "</x14:sparklineGroup></x14:sparklineGroups>";
    }

    /**
     * Copies the group into the worksheet's extension list, reusing the sparkline extension when the
     * sheet already has one — Excel expects a single extension per uri, and a second one is exactly
     * the sort of thing that turns into a repair prompt.
     * <p>
     * The whole {@code <ext>} is built as text and copied in rather than made with
     * {@code addNewExt()} and filled, which puts the x14 declaration on the extension element where
     * Excel's own files carry it. That layout is what has been verified against Excel; the thing
     * that actually decided recognition, though, was the uri's casing — see
     * {@link #SPARKLINE_EXT_URI}.
     */
    private void addGroup(XSSFSheet sheet, String groupsXml) throws XmlException {
        CTWorksheet worksheet = sheet.getCTWorksheet();
        CTExtensionList extensions = worksheet.isSetExtLst()
                ? worksheet.getExtLst() : worksheet.addNewExtLst();

        CTExtension existing = null;
        for (CTExtension candidate : extensions.getExtArray()) {
            if (SPARKLINE_EXT_URI.equalsIgnoreCase(candidate.getUri())) {
                existing = candidate;
                break;
            }
        }

        if (existing == null) {
            String extXml = "<ext xmlns=\"" + MAIN_NS + "\" xmlns:x14=\"" + X14_NS + "\" uri=\""
                    + SPARKLINE_EXT_URI + "\">" + groupsXml + "</ext>";
            XmlObject parsed = XmlObject.Factory.parse(extXml);
            try (XmlCursor source = parsed.newCursor(); XmlCursor destination = extensions.newCursor()) {
                source.toFirstChild();
                destination.toEndToken();
                source.copyXml(destination);
            }
            return;
        }

        // An extension is already there: the new group joins the ones inside it rather than opening
        // a second sparklineGroups element beside them.
        XmlObject parsed = XmlObject.Factory.parse(groupsXml);
        try (XmlCursor source = parsed.newCursor(); XmlCursor destination = existing.newCursor()) {
            source.toFirstChild();
            source.toFirstChild();
            destination.toFirstChild();
            destination.toEndToken();
            source.copyXml(destination);
        }
    }

    private String type(String raw) {
        String keyword = CellStyles.keyword(raw);
        if (keyword == null) {
            return "line";
        }
        return switch (keyword) {
            case "line" -> "line";
            case COLUMN, "bar" -> COLUMN;
            case "winloss", "win_loss", "win/loss", STACKED -> STACKED;
            default -> throw new IllegalArgumentException("Unknown sparkline \"type\" \"" + raw
                    + "\" — use line, column or winLoss.");
        };
    }

    /** Excel stores the colour as ARGB text rather than as a colour element. */
    private String colour(String hex) {
        String cleaned = hex == null || hex.isBlank() ? DEFAULT_COLOR : hex;
        CellStyles.color(cleaned, "color");
        return "FF" + cleaned.replace("#", "").toUpperCase(Locale.ROOT);
    }

    private String prefix(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        int bang = raw.lastIndexOf('!');
        if (bang < 0) {
            return fallback;
        }
        // Unquoted by hand rather than by regex: "^'|'$" is what java:S5850 asks to be grouped and
        // java:S6395 then asks to be ungrouped, and neither rule is wrong about a pattern that did
        // not need to be a pattern. A sheet name arrives quoted when it has a space in it.
        String name = raw.substring(0, bang).trim();
        if (name.startsWith("'")) {
            name = name.substring(1);
        }
        if (name.endsWith("'")) {
            name = name.substring(0, name.length() - 1);
        }
        return name.replace("''", "'");
    }

    private String strip(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("\"dataRange\" is required — the numbers each"
                    + " sparkline is drawn from, e.g. \"B2:M13\".");
        }
        return raw.substring(raw.lastIndexOf('!') + 1);
    }

    /** A sheet name reaches the file inside XML, so its own markup characters have to be escaped. */
    private String escape(String value) {
        String escaped = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return escaped.matches("\\w+") ? escaped : "'" + escaped.replace("'", "''") + "'";
    }
}
