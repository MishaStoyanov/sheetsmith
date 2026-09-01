package com.ap0stole.sheetsmith.services.excel.actions.formula;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.formula.GroupByConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.CellStyles;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.io.IOException;

/**
 * Answers "how much per region" — one row per distinct value of a column, with the numbers beside it
 * added up. The summary is written as real cells holding real formulas, so it lives in the sheet like
 * anything else: the preview shows it, a later step can format or sort it, and it recalculates when
 * the data underneath changes.
 * <p>
 * <b>Why this is not a pivot table.</b> POI can build one, and it survives a save — but the result
 * cells are not in the file at all. It writes a pivot cache marked {@code refreshOnLoad} and leaves
 * the sheet empty, so the numbers exist only after desktop Excel opens the file and refreshes it. In
 * this app the sheet is read back by POI and previewed by SheetJS in the browser, and neither
 * computes a pivot: the user would review a plan, apply it, and be shown an empty sheet. A summary
 * block is worth more than the name "pivot table".
 * <p>
 * The formulas are {@code SUMIF} and {@code COUNTIF} because those are in POI's function table.
 * {@code AVERAGEIF} is not — like every post-2007 function it needs an {@code _xlfn.} prefix to be
 * read back as a function rather than as an unknown name — so an average is written the long way, as
 * a sum over a count. {@code MINIFS}/{@code MAXIFS} have the same problem with no such workaround,
 * which is why min and max are refused here rather than written as something that opens broken.
 */
@Slf4j
@Component
public class GroupByHandler implements ActionHandler {

    /** The two functions this step offers, each read in four separate switches. */
    private static final String COUNT = "count";
    private static final String AVERAGE = "average";

    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Override
    public String getType() {
        return "GROUP_BY";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws IOException {
        GroupByConfig cfg = mapper.convertValue(properties, GroupByConfig.class);

        XSSFSheet data = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        CellRangeAddress area = CellStyles.area(cfg.getRange(), "range");
        boolean hasHeader = cfg.getHasHeader() == null || cfg.getHasHeader();

        int firstDataRow = hasHeader ? area.getFirstRow() + 1 : area.getFirstRow();
        if (firstDataRow > area.getLastRow()) {
            throw new IllegalArgumentException("\"range\" " + area.formatAsString() + " holds nothing"
                    + " but a header row — include the data rows to group.");
        }

        String function = function(cfg.getFunction());
        int keyColumn = column(cfg.getGroupBy(), "groupBy", area);
        int valueColumn = COUNT.equals(function) && isBlank(cfg.getValueColumn())
                ? keyColumn : column(cfg.getValueColumn(), "valueColumn", area);

        Map<String, Object> groups = distinctKeys(data, keyColumn, firstDataRow, area.getLastRow());
        if (groups.isEmpty()) {
            return "no values in " + ActionDescriptions.columnLetter(keyColumn)
                    + ", so there was nothing to group";
        }

        XSSFSheet target = targetSheet(workbook, data, cfg);
        CellReference start = targetStart(cfg, area, target, data);

        String keyRange = qualified(data, keyColumn, firstDataRow, area.getLastRow());
        String valueRange = qualified(data, valueColumn, firstDataRow, area.getLastRow());

        writeHeader(target, start, data, keyColumn, valueColumn, function, hasHeader);

        int row = start.getRow() + 1;
        for (Map.Entry<String, Object> group : groups.entrySet()) {
            XSSFCell key = cell(target, row, start.getCol());
            if (group.getValue() instanceof Double number) {
                key.setCellValue(number);
            } else {
                key.setCellValue(String.valueOf(group.getValue()));
            }
            String criteria = new CellReference(row, start.getCol()).formatAsString();
            cell(target, row, start.getCol() + 1).setCellFormula(
                    formula(function, keyRange, criteria, valueRange));
            row++;
        }

        int rows = area.getLastRow() - firstDataRow + 1;
        log.info("GROUP_BY summarised {} rows into {} groups on '{}'",
                rows, groups.size(), target.getSheetName());

        return groups.size() + (groups.size() == 1 ? " group" : " groups") + " from " + rows
                + " rows, written to " + target.getSheetName() + "!"
                + new CellReference(start.getRow(), start.getCol()).formatAsString();
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String range = ActionDescriptions.range(properties, "range");
        String groupBy = ActionDescriptions.text(properties, "groupBy");
        String value = ActionDescriptions.text(properties, "valueColumn");
        String function = CellStyles.keyword(ActionDescriptions.text(properties, "function"));
        String target = ActionDescriptions.text(properties, "targetSheet");

        String what = switch (function == null ? "sum" : function) {
            case COUNT -> "counting the rows";
            case AVERAGE, "avg", "mean" -> value == null ? "averaging the values" : "averaging column " + value;
            default -> value == null ? "totalling the values" : "totalling column " + value;
        };

        return ActionDescriptions.verb(tense, "Summarise", "Summarised") + " "
                + (range == null ? "the data" : range)
                + (groupBy == null ? "" : " by column " + groupBy) + ", " + what
                + (target == null ? "" : ", onto " + ActionDescriptions.quoted(target))
                + ActionDescriptions.sheetSuffix(properties);
    }

    private String formula(String function, String keyRange, String criteria, String valueRange) {
        return switch (function) {
            case COUNT -> "COUNTIF(" + keyRange + "," + criteria + ")";
            // Every key comes from the data, so its COUNTIF is at least one and this cannot divide
            // by zero — which is why it is written plainly rather than guarded.
            case AVERAGE -> "SUMIF(" + keyRange + "," + criteria + "," + valueRange + ")"
                    + "/COUNTIF(" + keyRange + "," + criteria + ")";
            default -> "SUMIF(" + keyRange + "," + criteria + "," + valueRange + ")";
        };
    }

    private String function(String raw) {
        String keyword = CellStyles.keyword(raw);
        if (keyword == null) {
            return "sum";
        }
        return switch (keyword) {
            case "sum", "total" -> "sum";
            case COUNT, "how many" -> COUNT;
            case AVERAGE, "avg", "mean" -> AVERAGE;
            case "min", "minimum", "max", "maximum" -> throw new IllegalArgumentException(
                    "\"function\" \"" + raw + "\" cannot be written as a formula Excel will read"
                            + " back — MINIFS and MAXIFS need a prefix POI does not write. Use sum,"
                            + " average or count.");
            default -> throw new IllegalArgumentException("Unknown \"function\" \"" + raw
                    + "\" — use sum, average or count.");
        };
    }

    /** Distinct keys in the order they first appear, keeping numbers as numbers so SUMIF matches. */
    private Map<String, Object> distinctKeys(XSSFSheet sheet, int column, int firstRow, int lastRow) {
        Map<String, Object> keys = new LinkedHashMap<>();
        for (int r = firstRow; r <= lastRow; r++) {
            XSSFRow row = sheet.getRow(r);
            Cell cell = row == null ? null : row.getCell(column);
            if (cell == null) {
                continue;
            }
            CellType type = cell.getCellType() == CellType.FORMULA
                    ? cell.getCachedFormulaResultType() : cell.getCellType();
            if (type == CellType.NUMERIC) {
                keys.putIfAbsent("n:" + cell.getNumericCellValue(), cell.getNumericCellValue());
            } else if (type == CellType.STRING) {
                String value = cell.getStringCellValue().trim();
                if (!value.isEmpty()) {
                    keys.putIfAbsent("s:" + value.toLowerCase(Locale.ROOT), value);
                }
            }
        }
        return keys;
    }

    private void writeHeader(XSSFSheet target, CellReference start, XSSFSheet data,
                             int keyColumn, int valueColumn, String function, boolean hasHeader) {
        String keyName = hasHeader ? headerOf(data, keyColumn) : null;
        String valueName = hasHeader ? headerOf(data, valueColumn) : null;
        String label = switch (function) {
            case COUNT -> "Count";
            case AVERAGE -> "Average";
            default -> "Total";
        };
        cell(target, start.getRow(), start.getCol()).setCellValue(
                keyName == null ? "Group" : keyName);
        cell(target, start.getRow(), start.getCol() + 1).setCellValue(
                valueName == null || COUNT.equals(function) ? label : label + " of " + valueName);
    }

    private String headerOf(XSSFSheet sheet, int column) {
        XSSFRow row = sheet.getRow(sheet.getFirstRowNum());
        Cell cell = row == null ? null : row.getCell(column);
        if (cell == null || cell.getCellType() != CellType.STRING) {
            return null;
        }
        String value = cell.getStringCellValue().trim();
        return value.isEmpty() ? null : value;
    }

    private XSSFSheet targetSheet(XSSFWorkbook workbook, XSSFSheet data, GroupByConfig cfg) {
        if (isBlank(cfg.getTargetSheet())) {
            return data;
        }
        String name = cfg.getTargetSheet().trim();
        XSSFSheet existing = workbook.getSheet(name);
        return existing != null ? existing : workbook.createSheet(name);
    }

    /**
     * Where the block starts: what was asked for, or — on the data's own sheet — two columns clear of
     * it, so a summary never lands on top of the rows it summarises.
     */
    private CellReference targetStart(GroupByConfig cfg, CellRangeAddress area,
                                      XSSFSheet target, XSSFSheet data) {
        if (!isBlank(cfg.getTargetCell())) {
            CellRangeAddress at = CellStyles.area(cfg.getTargetCell(), "targetCell");
            return new CellReference(at.getFirstRow(), at.getFirstColumn());
        }
        if (target != data) {
            return new CellReference(0, 0);
        }
        return new CellReference(area.getFirstRow(), area.getLastColumn() + 2);
    }

    /** Absolute and sheet-qualified: the summary often lives on a different sheet from the data. */
    private String qualified(XSSFSheet sheet, int column, int firstRow, int lastRow) {
        String letter = ActionDescriptions.columnLetter(column);
        String name = sheet.getSheetName();
        String prefix = name.matches("\\w+") ? name : "'" + name.replace("'", "''") + "'";
        return prefix + "!$" + letter + "$" + (firstRow + 1) + ":$" + letter + "$" + (lastRow + 1);
    }

    /** A column letter, or its 1-based position inside the range — the same rule LOOKUP uses. */
    private int column(String raw, String key, CellRangeAddress area) {
        if (isBlank(raw)) {
            throw new IllegalArgumentException("\"" + key + "\" says which column to use — either its"
                    + " letter (e.g. \"C\") or its position in \"range\" (e.g. 3).");
        }
        String cleaned = raw.trim();
        int index;
        if (cleaned.matches("\\d+")) {
            index = area.getFirstColumn() + Integer.parseInt(cleaned) - 1;
        } else if (cleaned.matches("(?i)[a-z]{1,3}")) {
            index = CellReference.convertColStringToIndex(cleaned.toUpperCase(Locale.ROOT));
        } else {
            throw new IllegalArgumentException("\"" + key + "\" \"" + raw + "\" is neither a column"
                    + " letter nor a position — use \"C\" or 3.");
        }
        if (index < area.getFirstColumn() || index > area.getLastColumn()) {
            throw new IllegalArgumentException("\"" + key + "\" \"" + raw + "\" is outside \"range\" "
                    + area.formatAsString() + ".");
        }
        return index;
    }

    private XSSFCell cell(XSSFSheet sheet, int row, int column) {
        XSSFRow r = sheet.getRow(row);
        if (r == null) {
            r = sheet.createRow(row);
        }
        XSSFCell cell = r.getCell(column);
        return cell == null ? r.createCell(column) : cell;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
