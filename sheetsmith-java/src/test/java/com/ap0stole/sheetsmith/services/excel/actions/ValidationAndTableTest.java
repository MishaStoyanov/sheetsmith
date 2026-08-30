package com.ap0stole.sheetsmith.services.excel.actions;

import com.ap0stole.sheetsmith.services.excel.actions.table.CreateTableHandler;
import com.ap0stole.sheetsmith.services.excel.actions.table.DataValidationHandler;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFTable;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two actions that change a sheet's future rather than its contents: what may be typed into it,
 * and whether the block of cells is a real table Excel will keep extending.
 */
class ValidationAndTableTest {

    private XSSFWorkbook workbook;
    private XSSFSheet sheet;
    private final DataValidationHandler validation = new DataValidationHandler();
    private final CreateTableHandler table = new CreateTableHandler();

    @BeforeEach
    void setUp() {
        workbook = new XSSFWorkbook();
        sheet = workbook.createSheet("Data");
        String[] headers = {"Product", "Amount", "Status"};
        XSSFRow header = sheet.createRow(0);
        for (int c = 0; c < headers.length; c++) {
            header.createCell(c).setCellValue(headers[c]);
        }
        for (int r = 1; r <= 3; r++) {
            XSSFRow row = sheet.createRow(r);
            row.createCell(0).setCellValue("Item " + r);
            row.createCell(1).setCellValue(r * 100.0);
            row.createCell(2).setCellValue("Open");
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        workbook.close();
    }

    private Map<String, Object> props(Object... pairs) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            properties.put((String) pairs[i], pairs[i + 1]);
        }
        return properties;
    }

    // ── DATA_VALIDATION ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a dropdown lands on the range with its options and a visible arrow")
    void addsADropdown() throws Exception {
        String detail = validation.execute(workbook, props(
                "range", "C2:C100", "type", "list", "values", "Open,In progress,Done"));

        assertThat(sheet.getDataValidations()).hasSize(1);
        DataValidation added = sheet.getDataValidations().getFirst();
        assertThat(added.getValidationConstraint().getExplicitListValues())
                .containsExactly("Open", "In progress", "Done");
        assertThat(added.getSuppressDropDownArrow())
                .as("a dropdown nobody can see is a dropdown nobody uses")
                .isFalse();
        assertThat(detail).contains("existing values are not checked");
    }

    @Test
    @DisplayName("a long list is refused with the fix, not written into a file Excel will repair")
    void refusesAnOverlongExplicitList() {
        String many = "Option ".repeat(1).concat(String.join(",",
                java.util.Collections.nCopies(40, "LongishOption")));

        var properties = props( "range", "C2:C100", "type", "list", "values", many);
        assertThatThrownBy(() -> validation.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceRange");
    }

    @Test
    @DisplayName("a range of cells is the other way to give a dropdown its options")
    void acceptsASourceRange() throws Exception {
        validation.execute(workbook, props(
                "range", "C2:C100", "type", "list", "sourceRange", "$F$1:$F$5"));

        assertThat(sheet.getDataValidations().getFirst().getValidationConstraint().getFormula1())
                .isEqualTo("$F$1:$F$5");
    }

    @Test
    @DisplayName("a numeric bound becomes a between-rule with both ends")
    void addsANumericRule() throws Exception {
        validation.execute(workbook, props(
                "range", "B2:B100", "type", "whole", "min", "0", "max", "1000"));

        DataValidationConstraint constraint =
                sheet.getDataValidations().getFirst().getValidationConstraint();
        assertThat(constraint.getOperator()).isEqualTo(DataValidationConstraint.OperatorType.BETWEEN);
        assertThat(constraint.getFormula1()).isEqualTo("0");
        assertThat(constraint.getFormula2()).isEqualTo("1000");
    }

    @Test
    @DisplayName("an operator by name works, so \"at least 1\" needs no second bound")
    void honoursTheOperator() throws Exception {
        validation.execute(workbook, props(
                "range", "B2:B100", "type", "decimal", "operator", "greaterThan", "value", "0"));

        assertThat(sheet.getDataValidations().getFirst().getValidationConstraint().getOperator())
                .isEqualTo(DataValidationConstraint.OperatorType.GREATER_THAN);
    }

    @Test
    @DisplayName("strict false warns instead of refusing — for a sheet that already breaks the rule")
    void strictnessIsSelectable() throws Exception {
        validation.execute(workbook, props(
                "range", "C2:C100", "type", "list", "values", "Open,Done", "strict", false));

        assertThat(sheet.getDataValidations().getFirst().getErrorStyle())
                .isEqualTo(DataValidation.ErrorStyle.WARNING);
    }

    @Test
    @DisplayName("a dropdown with no options is refused rather than added and useless")
    void aDropdownNeedsOptions() {
        var properties = props("range", "C2:C100", "type", "list");
        assertThatThrownBy(() -> validation.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs its options");
    }

    @Test
    @DisplayName("an unknown type or operator is named rather than quietly ignored")
    void unknownValuesAreRefused() {
        var properties = props("range", "C2:C9", "type", "colour");
        assertThatThrownBy(() -> validation.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("colour");
        var properties2 = props("range", "B2:B9", "type", "whole", "operator", "roughly", "min", "1");
        assertThatThrownBy(() -> validation.execute(workbook, properties2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("roughly");
    }

    // ── CREATE_TABLE ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("the range becomes a named table whose columns carry the header names")
    void createsATable() throws Exception {
        String detail = table.execute(workbook, props("range", "A1:C4", "name", "Sales"));

        assertThat(sheet.getTables()).hasSize(1);
        XSSFTable created = sheet.getTables().getFirst();
        assertThat(created.getName()).isEqualTo("Sales");
        assertThat(created.getCTTable().getTableColumns().getTableColumnList())
                .extracting(c -> c.getName())
                .containsExactly("Product", "Amount", "Status");
        assertThat(detail).contains("Sales[column]");
    }

    @Test
    @DisplayName("a blank heading is filled in, because Excel will not open a table with one")
    void blankHeadersAreFilled() throws Exception {
        sheet.getRow(0).getCell(2).setCellValue("");

        String detail = table.execute(workbook, props("range", "A1:C4"));

        assertThat(sheet.getRow(0).getCell(2).getStringCellValue()).isEqualTo("Column C");
        assertThat(detail).contains("1 blank heading was filled in");
    }

    @Test
    @DisplayName("a repeated heading is numbered, for the same reason")
    void duplicateHeadersAreNumbered() throws Exception {
        sheet.getRow(0).getCell(2).setCellValue("Amount");

        String detail = table.execute(workbook, props("range", "A1:C4"));

        assertThat(sheet.getRow(0).getCell(2).getStringCellValue()).isEqualTo("Amount 2");
        assertThat(detail).contains("repeated one was numbered");
    }

    @Test
    @DisplayName("a name Excel would reject is cleaned, and a colliding one is made unique")
    void tableNamesAreMadeLegal() throws Exception {
        table.execute(workbook, props("range", "A1:C4", "name", "2024 Sales!"));
        assertThat(sheet.getTables().getFirst().getName()).isEqualTo("Table_2024_Sales_");

        XSSFSheet second = workbook.createSheet("More");
        second.createRow(0).createCell(0).setCellValue("Product");
        second.createRow(1).createCell(0).setCellValue("Item");
        table.execute(workbook, props("range", "A1:A2", "name", "Table_2024_Sales_", "sheetName", "More"));

        assertThat(second.getTables().getFirst().getName()).isEqualTo("Table_2024_Sales_2");
    }

    @Test
    @DisplayName("two tables cannot share a cell — Excel repairs such a file and loses one")
    void overlappingTablesAreRefused() throws Exception {
        table.execute(workbook, props("range", "A1:C4"));

        var properties = props("range", "B1:C4");
        assertThatThrownBy(() -> table.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot overlap");
    }

    @Test
    @DisplayName("a header row with no data under it is not a table")
    void aSingleRowIsRefused() {
        var properties = props("range", "A1:C1");
        assertThatThrownBy(() -> table.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("single row");
    }

    // ── describe() ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the plan cards say what will be allowed, and what becomes a table")
    void describeReadsAsASentence() {
        assertThat(validation.describe(props("range", "C2:C100", "values", "Open,Done"), StepTense.IMPERATIVE))
                .isEqualTo("Limit C2:C100 to a dropdown of Open,Done");
        assertThat(validation.describe(props("range", "B2:B9", "type", "whole", "min", "0", "max", "10"),
                StepTense.PAST))
                .isEqualTo("Limited B2:B9 to a whole number between 0 and 10");
        assertThat(table.describe(props("range", "A1:C4", "name", "Sales"), StepTense.IMPERATIVE))
                .isEqualTo("Turn A1:C4 into a table named \"Sales\"");
    }

    @Test
    @DisplayName("a one-sided rule reads as what it allows, not just as \"a number\"")
    void describeReadsAOneSidedRule() {
        // Found by running it: with "value" and an operator the card said only "to a number",
        // which tells a reviewer nothing about what was allowed.
        assertThat(validation.describe(props("range", "B2:B11", "type", "decimal",
                "operator", "greaterOrEqual", "value", "0"), StepTense.PAST))
                .isEqualTo("Limited B2:B11 to a number of at least 0");
        assertThat(validation.describe(props("range", "B2:B11", "type", "whole",
                "operator", "lessThan", "value", "100"), StepTense.IMPERATIVE))
                .isEqualTo("Limit B2:B11 to a whole number less than 100");
    }

    @Test
    @DisplayName("describe survives the keys a model gets wrong")
    void describeNeverThrows() {
        assertThat(validation.describe(Map.of(), StepTense.PAST)).isNotBlank();
        assertThat(validation.describe(props("range", 2, "type", 7), StepTense.PAST)).isNotBlank();
        assertThat(table.describe(Map.of(), StepTense.IMPERATIVE)).isNotBlank();
    }
}
