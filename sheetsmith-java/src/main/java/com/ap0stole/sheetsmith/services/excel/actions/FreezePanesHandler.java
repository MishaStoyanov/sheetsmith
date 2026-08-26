package com.ap0stole.sheetsmith.services.excel.actions;

import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.ap0stole.sheetsmith.services.excel.model.FreezePanesConfig;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Keeps the top rows and left columns in view while the rest of the sheet scrolls.
 * <p>
 * Both keys are counts taken from the top-left, which is what makes "freeze the header" answerable
 * when the header is not row 1: a header on row 3 is {@code rows: 3}, freezing rows 1 to 3 together,
 * since the rows above a header are titles the user wants pinned too. Counts also dodge the
 * 0-based/1-based question an index would raise.
 * <p>
 * {@code rows: 0, columns: 0} unfreezes — POI reads that pair as "remove the pane", and every action
 * here needs a way back. That spelling is the <em>only</em> route to it: because clamping a request
 * down to the sheet's real size could otherwise land on (0,0) too, a freeze of an empty sheet leaves
 * the sheet untouched rather than quietly removing a pane the user already had.
 */
@Slf4j
@Component
public class FreezePanesHandler implements ActionHandler {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getType() {
        return "FREEZE_PANES";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws Exception {
        FreezePanesConfig cfg = mapper.convertValue(properties, FreezePanesConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        // Naming neither is the common case and means the common intent: pin the header row.
        int rows = cfg.getRows() == null && cfg.getColumns() == null ? 1 : count(cfg.getRows(), "rows");
        int columns = count(cfg.getColumns(), "columns");

        // An explicit unfreeze is never clamped: (0,0) is how it is spelled, and it has to work on
        // any sheet including an empty one.
        if (rows == 0 && columns == 0) {
            sheet.createFreezePane(0, 0);
            log.info("FREEZE_PANES removed the pane on '{}'", sheet.getSheetName());
            return null;
        }

        // A split past the content is a split past the sheet, and Excel offers to repair a file whose
        // ySplit sits beyond its last row. Clamped to one short of the content: freezing every row
        // there is leaves nothing to scroll, which is no reading of "freeze the header" at all, and
        // staying inside the content also keeps topLeftCell off Excel's row limit.
        int freezableRows = Math.max(usedRows(sheet) - 1, 0);
        int freezableColumns = Math.max(usedColumns(sheet) - 1, 0);
        int splitRows = Math.min(rows, freezableRows);
        int splitColumns = Math.min(columns, freezableColumns);

        // Clamping must never arrive at (0,0): POI reads that pair as "remove the pane", so a
        // request to FREEZE would silently destroy a pane the sheet already had. Only the explicit
        // spelling above may unfreeze, so here the sheet is left exactly as it is.
        if (splitRows == 0 && splitColumns == 0) {
            log.info("FREEZE_PANES asked for {}x{} on '{}', which has no content to freeze — left as it was",
                    rows, columns, sheet.getSheetName());
            return "the sheet has nothing to freeze, so the panes were left as they were";
        }

        // POI takes the column split first — the opposite order to how this action names them.
        sheet.createFreezePane(splitColumns, splitRows);
        log.info("FREEZE_PANES on '{}': {} row(s), {} column(s) (asked for {}x{})",
                sheet.getSheetName(), splitRows, splitColumns, rows, columns);

        return splitRows == rows && splitColumns == columns
                ? null
                : "the sheet can keep at most " + freezableRows + " row(s) and " + freezableColumns
                + " column(s) in view and still scroll, so " + splitRows + " row(s) and "
                + splitColumns + " column(s) were frozen";
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        Integer rawRows = ActionDescriptions.integer(properties, "rows");
        Integer rawColumns = ActionDescriptions.integer(properties, "columns");

        // A negative count is a request execute() will reject. Clamping it to 0 here would read as
        // "Unfreeze the panes" — a plan card stating the opposite of what was asked, which a user
        // could approve and then watch fail. Say only what is certain: this is a freeze.
        if ((rawRows != null && rawRows < 0) || (rawColumns != null && rawColumns < 0)) {
            return ActionDescriptions.verb(tense, "Freeze", "Froze") + " the panes"
                    + ActionDescriptions.sheetSuffix(properties);
        }

        int rows = rawRows == null && rawColumns == null ? 1 : rawRows == null ? 0 : rawRows;
        int columns = rawColumns == null ? 0 : rawColumns;

        if (rows == 0 && columns == 0) {
            return ActionDescriptions.verb(tense, "Unfreeze", "Unfroze") + " the panes"
                    + ActionDescriptions.sheetSuffix(properties);
        }

        StringBuilder what = new StringBuilder();
        if (rows == 1) {
            what.append("the top row");
        } else if (rows > 1) {
            what.append("the top ").append(rows).append(" rows");
        }
        if (columns > 0) {
            if (!what.isEmpty()) {
                what.append(" and ");
            }
            what.append(columns == 1 ? "the first column" : "the first " + columns + " columns");
        }
        return ActionDescriptions.verb(tense, "Freeze", "Froze") + " " + what
                + ActionDescriptions.sheetSuffix(properties);
    }

    /** Rows the sheet actually has, so a freeze can never be asked for past the end of it. */
    private int usedRows(XSSFSheet sheet) {
        return sheet.getPhysicalNumberOfRows() == 0 ? 0 : sheet.getLastRowNum() + 1;
    }

    private int usedColumns(XSSFSheet sheet) {
        int last = 0;
        for (Row row : sheet) {
            last = Math.max(last, row.getLastCellNum());
        }
        return Math.max(last, 0);
    }

    private int count(Integer value, String key) {
        if (value == null) {
            return 0;
        }
        if (value < 0) {
            throw new IllegalArgumentException("\"" + key + "\" cannot be negative — use 0 to freeze none.");
        }
        return value;
    }
}
