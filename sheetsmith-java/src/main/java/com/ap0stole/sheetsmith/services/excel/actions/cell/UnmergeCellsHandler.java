package com.ap0stole.sheetsmith.services.excel.actions.cell;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.cell.MergeConfig;
import com.ap0stole.sheetsmith.services.excel.actions.structure.StructureShift;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.io.IOException;

/**
 * Splits merged cells back apart — the undo MERGE_CELLS never had, and the fix for a sheet that
 * arrived merged.
 * <p>
 * Merged cells are the reason several other actions have awkward rules: SET_CELL_VALUE has to skip
 * the cells a merge swallows, AUTOSIZE_COLUMNS has to exclude merged regions from its measurement,
 * and sorting a merged block is not defined at all. So "unmerge everything first" is a real repair
 * step, and omitting {@code range} means exactly that — every merge on the sheet.
 * <p>
 * A named range unmerges every region it <em>touches</em>, which is what Excel does with a
 * selection: half a merged block cannot stay merged. The value survives in the region's top-left
 * cell, where it already lived — the other cells of a merge hold nothing to lose.
 */
@Slf4j
@Component
public class UnmergeCellsHandler implements ActionHandler {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getType() {
        return "UNMERGE_CELLS";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws IOException {
        MergeConfig cfg = mapper.convertValue(properties, MergeConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        boolean named = cfg.getRange() != null && !cfg.getRange().isBlank();
        CellRangeAddress area = named ? area(cfg.getRange()) : null;

        List<CellRangeAddress> merged = sheet.getMergedRegions();
        if (merged.isEmpty()) {
            return "there were no merged cells" + (named ? " in " + area.formatAsString() : "")
                    + ", so nothing changed";
        }

        // Collected as indices and removed in one call: removeMergedRegion renumbers the rest, so
        // removing them one at a time by index takes out the wrong regions.
        List<Integer> doomed = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < merged.size(); i++) {
            CellRangeAddress region = merged.get(i);
            if (area == null || region.intersects(area)) {
                doomed.add(i);
                labels.add(region.formatAsString());
            }
        }

        if (doomed.isEmpty()) {
            return "no merged cells overlap " + area.formatAsString() + ", so nothing changed";
        }
        sheet.removeMergedRegions(doomed);

        log.info("UNMERGE_CELLS split {} region(s) on '{}': {}",
                doomed.size(), sheet.getSheetName(), labels);

        String detail = doomed.size() + (doomed.size() == 1 ? " merged region split" : " merged regions split")
                + " (" + String.join(", ", labels.subList(0, Math.min(labels.size(), 5)))
                + (labels.size() > 5 ? ", …" : "") + ")";
        // Worth saying once: a user looking at the result sees three empty cells beside the value
        // and can mistake it for data loss.
        return detail + "; each value stayed in its region's top-left cell";
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String range = ActionDescriptions.range(properties, "range");
        return ActionDescriptions.verb(tense, "Unmerge", "Unmerged") + " "
                + (range == null ? "every merged block" : range)
                + ActionDescriptions.sheetSuffix(properties);
    }

    /**
     * A range that may name whole rows or columns, unlike the styling actions': unmerging column A
     * costs nothing per row, so {@code "A:A"} is a reasonable way to say "every merge in this
     * column" rather than something that would create a million cells.
     */
    private CellRangeAddress area(String raw) {
        String cleaned = raw.substring(raw.lastIndexOf('!') + 1).replace("$", "").trim();
        if (cleaned.matches("(?i)[A-Z]{1,3}(:[A-Z]{1,3})?")) {
            int colon = cleaned.indexOf(':');
            String first = colon < 0 ? cleaned : cleaned.substring(0, colon);
            String last = colon < 0 ? cleaned : cleaned.substring(colon + 1);
            return CellRangeAddress.valueOf(first + "1:" + last + StructureShift.EXCEL_MAX_ROWS);
        }
        if (cleaned.matches("\\d+(:\\d+)?")) {
            int colon = cleaned.indexOf(':');
            String first = colon < 0 ? cleaned : cleaned.substring(0, colon);
            String last = colon < 0 ? cleaned : cleaned.substring(colon + 1);
            return CellRangeAddress.valueOf("A" + first + ":"
                    + StructureShift.columnName(StructureShift.EXCEL_MAX_COLUMNS - 1) + last);
        }
        return CellRangeAddress.valueOf(cleaned);
    }
}
