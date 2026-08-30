package com.ap0stole.sheetsmith.services.excel.actions.cell;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.CellValues;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.ap0stole.sheetsmith.services.excel.transform.ColumnTransform;
import com.ap0stole.sheetsmith.services.excel.transform.ColumnTransformRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.io.IOException;

/**
 * Rewrites every value of one column by a named rule.
 * <p>
 * The rules live in {@link ColumnTransformRegistry} rather than here, and the model picks one by
 * name instead of authoring a pattern: a plan card reading "Rewrite C2:C35001 as +1 (XXX) XXX-XXXX
 * phone numbers" can be reviewed before it runs, which one holding a regex cannot.
 * <p>
 * Values the rule cannot convert are left exactly as they were and counted, because a column that
 * was only three quarters converted must not come back reported as done.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransformColumnHandler implements ActionHandler {

    private final ColumnTransformRegistry transforms;

    @Override
    public String getType() {
        return "TRANSFORM_COLUMN";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws IOException {
        String operation = ActionDescriptions.text(properties, "operation");
        ColumnTransform transform = transforms.find(operation);
        if (transform == null) {
            throw new IllegalArgumentException("Unknown operation \"" + operation
                    + "\" — use one of: " + String.join(", ", transforms.operations()));
        }

        XSSFSheet sheet = SheetResolver.resolve(workbook,
                ActionDescriptions.text(properties, "sheetName"),
                ActionDescriptions.integer(properties, "sheetIndex"));

        CellRangeAddress source = singleColumn(properties, "range", true);
        CellRangeAddress target = singleColumn(properties, "targetRange", false);
        if (target != null && target.getLastRow() - target.getFirstRow() != source.getLastRow() - source.getFirstRow()) {
            throw new IllegalArgumentException(
                    "\"targetRange\" must cover the same number of rows as \"range\".");
        }

        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        int sourceColumn = source.getFirstColumn();
        int targetColumn = target == null ? sourceColumn : target.getFirstColumn();
        int targetOffset = target == null ? 0 : target.getFirstRow() - source.getFirstRow();

        int changed = 0;
        int skipped = 0;
        for (int r = source.getFirstRow(); r <= source.getLastRow(); r++) {
            String before = readText(sheet, r, sourceColumn, evaluator);
            // An empty cell is not a failed conversion — there was nothing there to convert.
            if (before == null || before.isBlank()) {
                continue;
            }

            Optional<String> after = transform.apply(before, properties);
            if (after.isEmpty()) {
                skipped++;
                continue;
            }
            if (target == null && after.get().equals(before)) {
                continue;
            }

            write(sheet, r + targetOffset, targetColumn, after.get(), transform.numeric());
            changed++;
        }

        log.info("TRANSFORM_COLUMN {} over {}: {} changed, {} left alone",
                transform.getType(), source.formatAsString(), changed, skipped);

        return detail(changed, skipped);
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String range = ActionDescriptions.range(properties, "range");
        String operation = ActionDescriptions.text(properties, "operation");
        String rule = transforms.describe(operation, properties);
        String verb = ActionDescriptions.verb(tense, "Rewrite", "Rewrote");

        StringBuilder text = new StringBuilder(verb)
                .append(' ')
                .append(range == null ? "the column" : range);
        if (!rule.isBlank()) {
            text.append(' ').append(rule);
        }

        String target = ActionDescriptions.range(properties, "targetRange");
        if (target != null) {
            text.append(", writing the result into ").append(target);
        }
        return text.append(ActionDescriptions.sheetSuffix(properties)).toString();
    }

    /** Wording the user reads next to the step, and the model reads in its trace. */
    private String detail(int changed, int skipped) {
        String done = changed + (changed == 1 ? " value changed" : " values changed");
        if (skipped == 0) {
            return done;
        }
        String them = skipped == 1 ? "it" : "them";
        String were = skipped == 1 ? "it was" : "they were";
        return done + ", " + skipped + " left as " + were
                + " (the rule could not convert " + them + ")";
    }

    private CellRangeAddress singleColumn(Map<String, Object> properties, String key, boolean required) {
        String raw = ActionDescriptions.text(properties, key);
        if (raw == null) {
            if (required) {
                throw new IllegalArgumentException("\"" + key + "\" is required, e.g. \"C2:C500\".");
            }
            return null;
        }

        CellRangeAddress range = CellRangeAddress.valueOf(
                raw.substring(raw.lastIndexOf('!') + 1).replace("$", "").trim());
        if (range.getFirstColumn() != range.getLastColumn()) {
            throw new IllegalArgumentException("\"" + key + "\" must cover a single column, but "
                    + range.formatAsString() + " spans several. Use one step per column.");
        }
        return range;
    }

    private String readText(XSSFSheet sheet, int rowIdx, int colIdx, FormulaEvaluator evaluator) {
        Row row = sheet.getRow(rowIdx);
        return row == null ? null : CellValues.asText(CellValues.of(row.getCell(colIdx), evaluator));
    }

    private void write(XSSFSheet sheet, int rowIdx, int colIdx, String value, boolean numeric) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) row = sheet.createRow(rowIdx);
        Cell cell = row.getCell(colIdx);
        if (cell == null) cell = row.createCell(colIdx);

        if (numeric) {
            try {
                cell.setCellValue(Double.parseDouble(value));
                return;
            } catch (NumberFormatException _) {
                // The rule promised a number and produced something else; text keeps the value
                // visible rather than blanking the cell.
                log.debug("Numeric transform produced non-numeric text: {}", value);
            }
        }
        cell.setCellValue(value);
    }
}
