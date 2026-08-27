package com.ap0stole.sheetsmith.services.excel.actions.view;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.view.GroupRowsConfig;
import com.ap0stole.sheetsmith.services.excel.actions.structure.StructureShift;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Folds a block of rows into an outline with a +/- button beside it, so a long sheet can be read at
 * the level someone cares about — detail rows tucked away under the total that summarises them.
 * <p>
 * Two things about POI decide the shape of this. {@code groupRow} <em>creates</em> every row in the
 * span it is handed, so grouping "5:1000000" would materialise a million rows rather than fail: the
 * span is clamped to what the sheet actually holds and the difference is reported. And
 * {@code summaryBelow} is a property of the whole sheet, not of one group — Excel puts the button on
 * the summary row, which it assumes sits below the detail, so a sheet whose totals sit on top needs
 * this turned off once and it then applies to every group on that sheet.
 */
@Slf4j
@Component
public class GroupRowsHandler implements ActionHandler {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getType() {
        return "GROUP_ROWS";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws Exception {
        GroupRowsConfig cfg = mapper.convertValue(properties, GroupRowsConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        int[] span = StructureShift.rowSpan(cfg.getRange(), cfg.getAt(), cfg.getCount());
        int first = span[0];
        int last = span[1];
        boolean ungrouping = Boolean.TRUE.equals(cfg.getUngroup());

        if (sheet.getPhysicalNumberOfRows() == 0) {
            return "the sheet is empty, so there was nothing to " + (ungrouping ? "ungroup" : "group");
        }
        int lastRow = sheet.getLastRowNum();
        if (first > lastRow) {
            return "the sheet ends at row " + (lastRow + 1) + ", so there was nothing to "
                    + (ungrouping ? "ungroup" : "group");
        }
        int clamped = Math.min(last, lastRow);

        if (cfg.getSummaryBelow() != null) {
            sheet.setRowSumsBelow(cfg.getSummaryBelow());
        }

        if (ungrouping) {
            sheet.ungroupRow(first, clamped);
        } else {
            sheet.groupRow(first, clamped);
            if (Boolean.TRUE.equals(cfg.getCollapsed())) {
                sheet.setRowGroupCollapsed(first, true);
            }
        }

        log.info("GROUP_ROWS {} rows {}-{} on '{}'",
                ungrouping ? "ungrouped" : "grouped", first + 1, clamped + 1, sheet.getSheetName());

        return clamped < last
                ? "the sheet ends at row " + (lastRow + 1) + ", so rows " + (clamped + 2) + "–"
                + (last + 1) + " were left alone"
                : null;
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        boolean ungrouping = ActionDescriptions.flag(properties, "ungroup", false);
        String what = ActionDescriptions.rowSpan(properties);

        if (ungrouping) {
            return ActionDescriptions.verb(tense, "Ungroup", "Ungrouped") + " " + what
                    + ActionDescriptions.sheetSuffix(properties);
        }
        return ActionDescriptions.verb(tense, "Group", "Grouped") + " " + what
                + (ActionDescriptions.flag(properties, "collapsed", false) ? " and fold them away" : "")
                + ActionDescriptions.sheetSuffix(properties);
    }

}
