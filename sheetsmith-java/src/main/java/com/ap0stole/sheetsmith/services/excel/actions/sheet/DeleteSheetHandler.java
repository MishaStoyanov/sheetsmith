package com.ap0stole.sheetsmith.services.excel.actions.sheet;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.SheetTargetConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.io.IOException;

/**
 * Removes a whole sheet — the counterpart ADD_SHEET never had.
 * <p>
 * It is the most destructive action in the engine, so it is the one that guesses least. Every other
 * action falls back to the first sheet when told nothing, which is a convenience there and a way to
 * lose a workbook's front page here, so a target has to be named. A workbook must also keep at least
 * one sheet: Excel refuses to open a file with none, and POI will happily write one.
 * <p>
 * Formulas elsewhere that read from the sheet are named in the result, but found differently from
 * the way the row and column deletions find theirs — see {@link #formulasReferencing}. A chart drawn
 * from the sheet's data is not repaired either, for the same reason it survives a shift unchanged:
 * its ranges live in the drawing part rather than the formula table.
 */
@Slf4j
@Component
public class DeleteSheetHandler implements ActionHandler {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getType() {
        return "DELETE_SHEET";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws IOException {
        SheetTargetConfig cfg = mapper.convertValue(properties, SheetTargetConfig.class);

        String name = cfg.getName() != null && !cfg.getName().isBlank()
                ? cfg.getName() : cfg.getSheetName();
        Integer index = cfg.getSheetIndex();
        if ((name == null || name.isBlank()) && index == null) {
            throw new IllegalArgumentException("\"name\" is required — deleting a sheet is not"
                    + " something to do to whichever sheet happens to be first.");
        }

        int target = resolve(workbook, name, index);

        if (workbook.getNumberOfSheets() == 1) {
            throw new IllegalArgumentException("\"" + workbook.getSheetName(target)
                    + "\" is the only sheet in the workbook, and a workbook cannot have none —"
                    + " clear its contents instead, or add a sheet first.");
        }

        String removed = workbook.getSheetName(target);
        List<String> orphaned = formulasReferencing(workbook, removed, target);
        workbook.removeSheetAt(target);

        log.info("DELETE_SHEET removed '{}'; {} sheet(s) remain, {} formula(s) orphaned",
                removed, workbook.getNumberOfSheets(), orphaned.size());

        String detail = "\"" + removed + "\" deleted; " + workbook.getNumberOfSheets()
                + (workbook.getNumberOfSheets() == 1 ? " sheet remains" : " sheets remain");
        if (orphaned.isEmpty()) {
            return detail;
        }
        return detail + ", and " + orphaned.size()
                + (orphaned.size() == 1 ? " formula referred to it (" : " formulas referred to it (")
                + String.join(", ", orphaned.subList(0, Math.min(orphaned.size(), 5)))
                + (orphaned.size() > 5 ? ", …" : "") + ") — Excel will show #REF! there";
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String name = ActionDescriptions.text(properties, "name");
        if (name == null) {
            name = ActionDescriptions.text(properties, "sheetName");
        }
        Integer index = ActionDescriptions.integer(properties, "sheetIndex");

        return ActionDescriptions.verb(tense, "Delete", "Deleted") + " " + which(name, index);
    }

    /**
     * Formulas on other sheets that name this one, found by reading the formula text.
     * <p>
     * The workbook-wide error scan every other destructive action uses cannot see these: it decides
     * a formula is broken by evaluating it, and a formula naming a missing sheet does not evaluate
     * to an error — it fails to evaluate at all, and the scanner treats an unevaluatable cell as
     * fine, which it has to, since POI implements fewer functions than Excel. So the damage is
     * found before the deletion rather than after it. POI leaves the formula text untouched, and
     * the {@code #REF!} the user is warned about is what Excel shows when it opens the file.
     */
    private List<String> formulasReferencing(XSSFWorkbook workbook, String name, int deleted) {
        // Both spellings of a sheet reference: Data!A1, and 'My Data'!A1 when the name needs quoting.
        String bare = name.toLowerCase(java.util.Locale.ROOT) + "!";
        String quoted = "'" + name.toLowerCase(java.util.Locale.ROOT) + "'!";
        List<String> found = new java.util.ArrayList<>();

        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            if (i == deleted) {
                continue;
            }
            for (org.apache.poi.ss.usermodel.Row row : workbook.getSheetAt(i)) {
                for (org.apache.poi.ss.usermodel.Cell cell : row) {
                    if (cell.getCellType() != org.apache.poi.ss.usermodel.CellType.FORMULA) {
                        continue;
                    }
                    String formula = cell.getCellFormula().toLowerCase(java.util.Locale.ROOT);
                    if (formula.contains(bare) || formula.contains(quoted)) {
                        found.add(workbook.getSheetName(i) + "!"
                                + new org.apache.poi.ss.util.CellReference(cell).formatAsString(false));
                    }
                }
            }
        }
        return found;
    }

    private String names(XSSFWorkbook workbook) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            text.append(i > 0 ? ", " : "").append('"').append(workbook.getSheetName(i)).append('"');
        }
        return text.toString();
    }

    /**
     * Which sheet the step means, by name where it gave one and by position otherwise.
     * <p>
     * Both misses are named rather than reported as "not found": a name that is not there is worth
     * listing the workbook's sheets for, and an index past the end is worth saying how many there
     * are — the model that got it wrong is the one reading the answer.
     */
    private int resolve(XSSFWorkbook workbook, String name, Integer index) {
        if (name != null && !name.isBlank()) {
            int found = workbook.getSheetIndex(name.trim());
            if (found < 0) {
                throw new IllegalArgumentException("There is no sheet named \"" + name.trim()
                        + "\" — the workbook has " + names(workbook) + ".");
            }
            return found;
        }
        if (index < 0 || index >= workbook.getNumberOfSheets()) {
            throw new IllegalArgumentException("Sheet index " + index + " is out of bounds —"
                    + " the workbook has " + workbook.getNumberOfSheets() + " sheet(s).");
        }
        return index;
    }

    /** Which sheet the card should name: by name, by position, or as "the sheet" when neither. */
    private static String which(String name, Integer index) {
        if (name != null) {
            return "the sheet " + ActionDescriptions.quoted(name);
        }
        return index != null ? "sheet " + (index + 1) : "the sheet";
    }
}
