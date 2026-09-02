package com.ap0stole.sheetsmith.services.excel.query;

import com.ap0stole.sheetsmith.configs.ConditionalOnChatEnabled;
import com.ap0stole.sheetsmith.configs.ChatConfig;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.ap0stole.sheetsmith.services.excel.query.model.ReadRangeConfig;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnChatEnabled
public class ReadRangeTool implements QueryTool {

    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final ChatConfig chatConfig;

    @Override
    public String getType() {
        return "READ_RANGE";
    }

    @Override
    public String promptSpec() {
        return """
                READ_RANGE
                   Keys: "range" (e.g. "A1:C10") — returns the raw cell values, formulas as their results
                   Optional: "sheetName", "sheetIndex"
                   Limited to %d cells per call; for anything bigger use AGGREGATE or FIND_ROWS.
                   It will NOT return the sheet. A range covering more than %d%% of the sheet's data is
                   refused unless it is a single column — read the one column you need, not the table.
                   If you cannot find something, do not read everything to look for it: say what you
                   searched for and ask the person to name the value more exactly.
                   Example: {"tool": "READ_RANGE", "args": {"range": "A1:C10", "sheetName": "Sales"}}"""
                .formatted(chatConfig.getMaxCells(), Math.round(chatConfig.getMaxReadShare() * 100));
    }

    /**
     * Refuses a read that would hand over the sheet rather than an answer from it.
     * <p>
     * The cell cap next to this one measures size; this measures share, and share is the promise.
     * A small sheet fits inside any reasonable size cap, so without this the whole of it can be
     * returned as "the result of a step" — which is true, and is not what anybody reading the
     * privacy line understands by it. It also costs what it costs: every cell returned is sent to
     * the model on this step and replayed on the next.
     * <p>
     * A single column is always allowed. It is the shape a search should take — read the names,
     * not the table — and on a narrow sheet one column is most of the cells, so measuring share
     * without this exception would refuse exactly the behaviour being encouraged.
     */
    private void guardCoverage(XSSFSheet sheet, CellRangeAddress range, int firstRow, int lastRow,
                               int firstCol, int lastCol, long cells) {
        if (firstCol == lastCol) {
            return;
        }
        long sheetCells = dataCells(sheet);
        if (sheetCells <= 0) {
            return;
        }
        double share = (double) cells / sheetCells;
        if (share <= chatConfig.getMaxReadShare()) {
            return;
        }
        throw new IllegalArgumentException("Range " + range.formatAsString() + " covers "
                + Math.round(share * 100) + "% of this sheet's data, and a single read may not go past "
                + Math.round(chatConfig.getMaxReadShare() * 100) + "% of it. Read one column instead, or use"
                + " FIND_ROWS or AGGREGATE to ask a question about the rows. If you are looking for"
                + " something you could not find, say so and ask for the value to be named exactly —"
                + " reading the sheet to search it by eye is not available.");
    }

    /** The sheet's own extent: rows that exist times the widest row, which is what "all of it" means. */
    private long dataCells(XSSFSheet sheet) {
        int lastRow = sheet.getLastRowNum();
        int widest = 0;
        for (int r = sheet.getFirstRowNum(); r <= lastRow; r++) {
            var row = sheet.getRow(r);
            if (row != null) {
                widest = Math.max(widest, row.getLastCellNum());
            }
        }
        int rows = lastRow - sheet.getFirstRowNum() + 1;
        return (long) Math.max(rows, 0) * Math.max(widest, 0);
    }

    @Override
    public QueryResult execute(XSSFWorkbook workbook, Map<String, Object> properties) {
        ReadRangeConfig cfg = mapper.convertValue(properties, ReadRangeConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        CellRangeAddress range = CellRangeAddress.valueOf(cfg.getRange());

        int firstRow = range.getFirstRow();
        int lastRow = range.getLastRow();
        int firstCol = range.getFirstColumn();
        int lastCol = range.getLastColumn();

        long cells = (long) (lastRow - firstRow + 1) * (lastCol - firstCol + 1);
        if (cells > chatConfig.getMaxCells()) {
            throw new IllegalArgumentException("Range " + range.formatAsString() + " covers " + cells
                    + " cells, limit is " + chatConfig.getMaxCells()
                    + " — request a smaller range or use AGGREGATE instead.");
        }

        guardCoverage(sheet, range, firstRow, lastRow, firstCol, lastCol, cells);

        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        List<List<Object>> values = new ArrayList<>();
        for (int r = firstRow; r <= lastRow; r++) {
            List<Object> row = new ArrayList<>();
            for (int c = firstCol; c <= lastCol; c++) {
                row.add(QuerySupport.cellValue(sheet, r, c, evaluator));
            }
            values.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sheet", sheet.getSheetName());
        data.put("range", range.formatAsString());
        data.put("values", values);

        log.info("READ_RANGE {} on sheet '{}' returned {} cells", range.formatAsString(), sheet.getSheetName(), cells);

        String summary = "Read %s on %s — %d row(s) x %d column(s)".formatted(
                range.formatAsString(), sheet.getSheetName(), values.size(), lastCol - firstCol + 1);
        return new QueryResult(QuerySupport.clip(summary, 120), data);
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String range = QuerySupport.propString(properties, "range");
        return QuerySupport.verb(tense, "Read", "Read") + " " + (range == null ? "a range" : range)
                + QuerySupport.sheetSuffix(properties);
    }
}
