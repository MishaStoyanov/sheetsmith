package com.ap0stole.sheetsmith.services.excel.actions.table;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.table.DataValidationConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.CellStyles;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFDataValidationHelper;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Constrains what may be typed into a range — a dropdown of allowed values, or a number, date or
 * length that has to sit inside a bound.
 * <p>
 * The first action here that changes a sheet's <em>future</em> rather than its contents, which is
 * why it exists: a plan that cleans up a status column and does nothing else is undone by the next
 * person who types "Compleet" into it.
 * <p>
 * The rule worth knowing is Excel's, not this code's: an explicit list is stored as one string in
 * the file and Excel refuses anything past 255 characters, silently in some versions and with a
 * repair prompt in others. Long lists therefore have to live in cells and be pointed at with
 * {@code sourceRange}, and the error here says so rather than letting the file break later.
 */
@Slf4j
@Component
public class DataValidationHandler implements ActionHandler {

    /** Excel's own ceiling on the stored list, including the separating commas. */
    private static final int MAX_LIST_LENGTH = 255;

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getType() {
        return "DATA_VALIDATION";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws Exception {
        DataValidationConfig cfg = mapper.convertValue(properties, DataValidationConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        CellRangeAddress area = CellStyles.area(cfg.getRange(), "range");
        XSSFDataValidationHelper helper = new XSSFDataValidationHelper(sheet);

        String type = CellStyles.keyword(cfg.getType()) == null ? "list" : CellStyles.keyword(cfg.getType());
        DataValidationConstraint constraint = switch (type) {
            case "list", "dropdown" -> listConstraint(helper, cfg);
            case "whole", "integer", "int" -> helper.createIntegerConstraint(
                    operator(cfg.getOperator()), bound(cfg.getMin(), cfg.getValue()), bound(cfg.getMax(), null));
            case "decimal", "number" -> helper.createDecimalConstraint(
                    operator(cfg.getOperator()), bound(cfg.getMin(), cfg.getValue()), bound(cfg.getMax(), null));
            case "date" -> helper.createDateConstraint(
                    operator(cfg.getOperator()), quoted(cfg.getMin() == null ? cfg.getValue() : cfg.getMin()),
                    quoted(cfg.getMax()), "yyyy-mm-dd");
            case "textlength", "length" -> helper.createTextLengthConstraint(
                    operator(cfg.getOperator()), bound(cfg.getMin(), cfg.getValue()), bound(cfg.getMax(), null));
            default -> throw new IllegalArgumentException("Unknown validation \"type\" \"" + cfg.getType()
                    + "\" — use list, whole, decimal, date or textLength.");
        };

        DataValidation validation = helper.createValidation(constraint,
                new CellRangeAddressList(area.getFirstRow(), area.getLastRow(),
                        area.getFirstColumn(), area.getLastColumn()));

        // A dropdown nobody can see is a dropdown nobody uses; POI defaults this off.
        validation.setSuppressDropDownArrow(false);
        validation.setEmptyCellAllowed(cfg.getAllowBlank() == null || cfg.getAllowBlank());

        // Rejecting outright is the point of the action, so it is the default — but a sheet being
        // cleaned up often already holds values the new rule forbids, and Excel only checks what is
        // typed after the rule exists, never what is already there.
        boolean strict = cfg.getStrict() == null || cfg.getStrict();
        validation.setErrorStyle(strict ? DataValidation.ErrorStyle.STOP : DataValidation.ErrorStyle.WARNING);
        validation.setShowErrorBox(true);
        validation.createErrorBox(
                cfg.getErrorTitle() == null || cfg.getErrorTitle().isBlank()
                        ? "Not an allowed value" : cfg.getErrorTitle(),
                cfg.getErrorMessage() == null || cfg.getErrorMessage().isBlank()
                        ? message(type, cfg) : cfg.getErrorMessage());

        sheet.addValidationData(validation);

        log.info("DATA_VALIDATION added a {} rule to {} on '{}'",
                type, area.formatAsString(), sheet.getSheetName());
        return "existing values are not checked — Excel applies this to what is typed from now on";
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String range = ActionDescriptions.range(properties, "range");
        String where = range == null ? "the cells" : range;
        String type = CellStyles.keyword(ActionDescriptions.text(properties, "type"));
        String values = ActionDescriptions.text(properties, "values");

        if (type == null || "list".equals(type) || "dropdown".equals(type)) {
            return ActionDescriptions.verb(tense, "Limit", "Limited") + " " + where + " to a dropdown"
                    + (values == null ? "" : " of " + values.trim())
                    + ActionDescriptions.sheetSuffix(properties);
        }
        // "value" is the one-sided form, and it carries its meaning in the operator rather than in
        // the key name — without this the card reads "Limited B2:B100 to a number", which tells a
        // reviewer nothing about what was actually allowed.
        String min = ActionDescriptions.text(properties, "min");
        String max = ActionDescriptions.text(properties, "max");
        String single = ActionDescriptions.text(properties, "value");
        String bounds;
        if (min != null && max != null) {
            bounds = " between " + min + " and " + max;
        } else if (min != null || max != null || single != null) {
            String bound = min != null ? min : max != null ? max : single;
            bounds = " " + comparison(CellStyles.keyword(
                    ActionDescriptions.text(properties, "operator")), min == null && max != null) + " " + bound;
        } else {
            bounds = "";
        }
        String noun = switch (type) {
            case "date" -> "a date";
            case "textlength", "length" -> "a text length";
            case "whole", "integer", "int" -> "a whole number";
            default -> "a number";
        };
        return ActionDescriptions.verb(tense, "Limit", "Limited") + " " + where + " to " + noun
                + bounds + ActionDescriptions.sheetSuffix(properties);
    }

    /** How an operator reads in a sentence a reviewer is meant to check. */
    private String comparison(String operator, boolean impliedUpper) {
        if (operator == null) {
            return impliedUpper ? "of at most" : "of at least";
        }
        return switch (operator) {
            case "greaterthan", "greater_than", ">" -> "greater than";
            case "lessthan", "less_than", "<" -> "less than";
            case "greaterorequal", "at_least", ">=" -> "of at least";
            case "lessorequal", "at_most", "<=" -> "of at most";
            case "equal", "equals", "=" -> "equal to";
            case "notequal", "not_equal", "!=" -> "other than";
            default -> "of at least";
        };
    }

    /**
     * A dropdown, from either a comma-separated list of values or a range of cells holding them.
     * The range form is what a long list needs, and what a list that should stay editable wants.
     */
    private DataValidationConstraint listConstraint(XSSFDataValidationHelper helper,
                                                    DataValidationConfig cfg) {
        if (cfg.getSourceRange() != null && !cfg.getSourceRange().isBlank()) {
            String source = cfg.getSourceRange().trim();
            return helper.createFormulaListConstraint(source.startsWith("=") ? source.substring(1) : source);
        }
        if (cfg.getValues() == null || cfg.getValues().isBlank()) {
            throw new IllegalArgumentException("A dropdown needs its options — give \"values\""
                    + " (e.g. \"Open,In progress,Done\") or \"sourceRange\" naming cells that hold them.");
        }
        List<String> options = Arrays.stream(cfg.getValues().split(","))
                .map(String::trim).filter(option -> !option.isEmpty()).toList();
        if (options.isEmpty()) {
            throw new IllegalArgumentException("\"values\" listed no options.");
        }
        int length = String.join(",", options).length();
        if (length > MAX_LIST_LENGTH) {
            throw new IllegalArgumentException("Excel stores an explicit dropdown as one "
                    + MAX_LIST_LENGTH + "-character string and this list is " + length
                    + " — put the options in cells and point at them with \"sourceRange\" instead.");
        }
        return helper.createExplicitListConstraint(options.toArray(String[]::new));
    }

    /** The default error text says what is allowed, which is the only useful thing it can say. */
    private String message(String type, DataValidationConfig cfg) {
        if ("list".equals(type) || "dropdown".equals(type)) {
            return cfg.getValues() == null
                    ? "Pick one of the values in the list."
                    : "Pick one of: " + cfg.getValues().trim() + ".";
        }
        String min = cfg.getMin() == null ? cfg.getValue() : cfg.getMin();
        if (min != null && cfg.getMax() != null) {
            return "Enter a value between " + min + " and " + cfg.getMax() + ".";
        }
        return min != null ? "Enter a value of at least " + min + "." : "That value is not allowed here.";
    }

    private int operator(String raw) {
        String cleaned = CellStyles.keyword(raw);
        if (cleaned == null) {
            return DataValidationConstraint.OperatorType.BETWEEN;
        }
        return switch (cleaned) {
            case "between" -> DataValidationConstraint.OperatorType.BETWEEN;
            case "notbetween", "not_between" -> DataValidationConstraint.OperatorType.NOT_BETWEEN;
            case "equal", "equals", "=" -> DataValidationConstraint.OperatorType.EQUAL;
            case "notequal", "not_equal", "!=" -> DataValidationConstraint.OperatorType.NOT_EQUAL;
            case "greaterthan", "greater_than", ">" -> DataValidationConstraint.OperatorType.GREATER_THAN;
            case "lessthan", "less_than", "<" -> DataValidationConstraint.OperatorType.LESS_THAN;
            case "greaterorequal", "at_least", ">=" -> DataValidationConstraint.OperatorType.GREATER_OR_EQUAL;
            case "lessorequal", "at_most", "<=" -> DataValidationConstraint.OperatorType.LESS_OR_EQUAL;
            default -> throw new IllegalArgumentException("Unknown \"operator\" \"" + raw
                    + "\" — use between, greaterThan, lessThan, greaterOrEqual, lessOrEqual or equal.");
        };
    }

    /** POI takes bounds as formula strings, so a missing one is null rather than an empty string. */
    private String bound(String primary, String fallback) {
        String value = primary != null && !primary.isBlank() ? primary : fallback;
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** A date bound is a literal in the file, and Excel wants it quoted as a date string. */
    private String quoted(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
