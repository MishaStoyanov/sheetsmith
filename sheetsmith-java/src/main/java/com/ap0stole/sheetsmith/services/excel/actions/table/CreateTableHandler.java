package com.ap0stole.sheetsmith.services.excel.actions.table;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.table.CreateTableConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.CellStyles;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFTable;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTTableStyleInfo;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a block of cells into a real Excel table: banded rows, filter arrows, and a name the rest of
 * the workbook can refer to.
 * <p>
 * FORMAT_CELLS and SET_BORDERS can make a range <em>look</em> like a table; this makes it one. The
 * difference shows up when the data changes — a table grows as rows are added, its filters follow,
 * and a formula can say {@code Sales[Amount]} instead of {@code B2:B500}.
 * <p>
 * Excel is strict about the header row in a way it is not about anything else: every column needs a
 * non-empty name and no two may match, or the file opens with a repair prompt. Rather than write a
 * file that provokes that, blank headers are filled and duplicates numbered — and the step says what
 * it had to change, because a header the user did not write is a surprise worth reporting.
 */
@Slf4j
@Component
public class CreateTableHandler implements ActionHandler {

    /** POI's own default banding style, and the one that reads as "a table" at a glance. */
    private static final String DEFAULT_STYLE = "TableStyleMedium2";

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getType() {
        return "CREATE_TABLE";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws Exception {
        CreateTableConfig cfg = mapper.convertValue(properties, CreateTableConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        CellRangeAddress area = CellStyles.area(cfg.getRange(), "range");

        if (area.getFirstRow() == area.getLastRow()) {
            throw new IllegalArgumentException(area.formatAsString() + " is a single row, which"
                    + " would be a table of headings with no data — include the data rows.");
        }
        // Two tables cannot share a cell; Excel repairs a file where they do, losing one of them.
        for (XSSFTable existing : sheet.getTables()) {
            CellRangeAddress taken = existing.getCellReferences() == null ? null
                    : CellRangeAddress.valueOf(existing.getCellReferences().formatAsString());
            if (taken != null && taken.intersects(area)) {
                throw new IllegalArgumentException("The table \"" + existing.getDisplayName()
                        + "\" already covers " + taken.formatAsString()
                        + ", and two tables cannot overlap.");
            }
        }

        String repaired = repairHeaders(sheet, area);

        XSSFTable table = sheet.createTable(new AreaReference(
                new CellReference(area.getFirstRow(), area.getFirstColumn()),
                new CellReference(area.getLastRow(), area.getLastColumn()),
                SpreadsheetVersion.EXCEL2007));
        // Reads the header cells into the table's column definitions; without it the columns are
        // named Column1, Column2 … and a structured reference names nothing recognisable.
        table.updateHeaders();

        String name = name(workbook, cfg.getName());
        table.setName(name);
        table.setDisplayName(name);

        CTTableStyleInfo style = table.getCTTable().isSetTableStyleInfo()
                ? table.getCTTable().getTableStyleInfo() : table.getCTTable().addNewTableStyleInfo();
        style.setName(cfg.getStyle() == null || cfg.getStyle().isBlank() ? DEFAULT_STYLE : cfg.getStyle().trim());
        style.setShowRowStripes(true);
        style.setShowColumnStripes(false);
        style.setShowFirstColumn(false);
        style.setShowLastColumn(false);

        log.info("CREATE_TABLE created \"{}\" over {} on '{}'",
                name, area.formatAsString(), sheet.getSheetName());

        String detail = "the table is named \"" + name + "\", so a formula can say " + name
                + "[column] instead of a range";
        return repaired == null ? detail : detail + "; " + repaired;
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String range = ActionDescriptions.range(properties, "range");
        String name = ActionDescriptions.text(properties, "name");
        return ActionDescriptions.verb(tense, "Turn", "Turned") + " "
                + (range == null ? "the data" : range) + " into a table"
                + (name == null ? "" : " named " + ActionDescriptions.quoted(name))
                + ActionDescriptions.sheetSuffix(properties);
    }

    /**
     * Makes the header row legal: no blanks, no repeats. Excel will not open a table with either,
     * and a repair prompt on a file this engine produced is worse than a heading called "Column C".
     *
     * @return what had to change, or null when the headers were already fine
     */
    private String repairHeaders(XSSFSheet sheet, CellRangeAddress area) {
        int headerRow = area.getFirstRow();
        Row row = sheet.getRow(headerRow);
        if (row == null) {
            row = sheet.createRow(headerRow);
        }

        Set<String> taken = new HashSet<>();
        int blanks = 0;
        int renamed = 0;

        for (int c = area.getFirstColumn(); c <= area.getLastColumn(); c++) {
            Cell cell = row.getCell(c);
            String header = cell == null ? "" : cell.toString().trim();
            if (header.isEmpty()) {
                header = "Column " + CellReference.convertNumToColString(c);
                blanks++;
            }
            String unique = header;
            for (int suffix = 2; !taken.add(unique.toLowerCase(Locale.ROOT)); suffix++) {
                unique = header + " " + suffix;
                renamed++;
            }
            if (cell == null) {
                cell = row.createCell(c);
            }
            if (!unique.equals(cell.toString())) {
                cell.setCellValue(unique);
            }
        }

        if (blanks == 0 && renamed == 0) {
            return null;
        }
        StringBuilder text = new StringBuilder("Excel needs every column of a table named and no two"
                + " the same, so ");
        if (blanks > 0) {
            text.append(blanks).append(blanks == 1 ? " blank heading was" : " blank headings were")
                    .append(" filled in");
        }
        if (renamed > 0) {
            text.append(blanks > 0 ? " and " : "").append(renamed)
                    .append(renamed == 1 ? " repeated one was" : " repeated ones were").append(" numbered");
        }
        return text.toString();
    }

    /**
     * A table name Excel accepts and the workbook does not already use: letters, digits and
     * underscores only, never starting with a digit, and never colliding — a duplicate name makes
     * the file unopenable rather than merely odd.
     */
    private String name(XSSFWorkbook workbook, String requested) {
        String base = requested == null || requested.isBlank() ? "Table" : requested.trim();
        base = base.replaceAll("[^A-Za-z0-9_]", "_");
        if (base.isEmpty() || Character.isDigit(base.charAt(0))) {
            base = "Table_" + base;
        }

        Set<String> used = new HashSet<>();
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            for (XSSFTable table : workbook.getSheetAt(i).getTables()) {
                used.add(table.getName().toLowerCase(Locale.ROOT));
            }
        }
        String unique = base;
        for (int suffix = 2; used.contains(unique.toLowerCase(Locale.ROOT)); suffix++) {
            unique = base + suffix;
        }
        return unique;
    }
}
