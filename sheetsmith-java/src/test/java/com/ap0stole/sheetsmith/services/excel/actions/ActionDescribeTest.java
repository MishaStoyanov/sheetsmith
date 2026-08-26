package com.ap0stole.sheetsmith.services.excel.actions;

import com.ap0stole.sheetsmith.configs.ProcessingConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.ap0stole.sheetsmith.services.excel.transform.ColumnTransformRegistry;
import com.ap0stole.sheetsmith.services.excel.transform.DigitsOnlyTransform;
import com.ap0stole.sheetsmith.services.excel.transform.LowerCaseTransform;
import com.ap0stole.sheetsmith.services.excel.transform.PadLeftTransform;
import com.ap0stole.sheetsmith.services.excel.transform.PhoneUsTransform;
import com.ap0stole.sheetsmith.services.excel.transform.RegexReplaceTransform;
import com.ap0stole.sheetsmith.services.excel.transform.ReplaceTransform;
import com.ap0stole.sheetsmith.services.excel.transform.SplitTakeTransform;
import com.ap0stole.sheetsmith.services.excel.transform.TitleCaseTransform;
import com.ap0stole.sheetsmith.services.excel.transform.ToNumberTransform;
import com.ap0stole.sheetsmith.services.excel.transform.TrimTransform;
import com.ap0stole.sheetsmith.services.excel.transform.UpperCaseTransform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ActionDescribeTest {

    /** Declared before HANDLERS on purpose: static initialisers run in order and one reads it. */
    private static final ColumnTransformRegistry TRANSFORMS = new ColumnTransformRegistry(List.of(
            new DigitsOnlyTransform(), new LowerCaseTransform(), new PadLeftTransform(),
            new PhoneUsTransform(), new RegexReplaceTransform(), new ReplaceTransform(),
            new SplitTakeTransform(), new TitleCaseTransform(), new ToNumberTransform(),
            new TrimTransform(), new UpperCaseTransform()));

    /**
     * Every handler in the registry belongs here. A handler missing from this list is silently
     * outside the "describe never throws over garbage input" guarantee below, which is the only
     * thing standing between the LLM's property maps and a user-facing crash.
     */
    private static final List<ActionHandler> HANDLERS = List.of(
            new AddFormulaHandler(),
            new AddSheetHandler(),
            new AddTotalsRowHandler(),
            new AlignCellsHandler(),
            new AutosizeColumnsHandler(new ProcessingConfig()),
            new ClearCellsHandler(),
            new CommentHandler(),
            new ColorScaleHandler(),
            new ConditionalFormattingHandler(),
            new DataBarsHandler(),
            new DeleteColumnsHandler(),
            new DeleteSheetHandler(),
            new DeleteRowsHandler(),
            new CreateChartHandler(),
            new CreateTableHandler(),
            new DataValidationHandler(),
            new FillFormulaHandler(),
            new FilterDataHandler(),
            new InsertColumnsHandler(),
            new InsertRowsHandler(),
            new LookupFromSheetHandler(),
            new FormatCellsHandler(),
            new FreezePanesHandler(),
            new GroupByHandler(),
            new GroupRowsHandler(),
            new HyperlinkHandler(),
            new MergeCellsHandler(),
            new NumberFormatHandler(),
            new PageSetupHandler(),
            new ProtectSheetHandler(),
            new RemoveDuplicatesHandler(),
            new RenameChartAxisHandler(),
            new RenameChartTitleHandler(),
            new RenameColumnHandler(),
            new RenameSheetHandler(),
            new SetBordersHandler(),
            new SetCellValueHandler(),
            new SortDataHandler(),
            new SparklineHandler(),
            new TransformColumnHandler(TRANSFORMS),
            new UnmergeCellsHandler());

    private static final List<String> ALL_KEYS = List.of(
            "range", "cell", "sheetName", "sheetIndex", "sourceRange", "sourceSheet", "targetSheet",
            "formula", "label", "name", "newName", "newTitle", "title", "axis", "chartType", "chartIndex",
            "columnIndex", "ascending", "operator", "value", "backgroundColor", "fontColor", "bold", "fontSize",
            "valueType", "maxWidth", "rows", "columns", "operation", "targetRange",
            // The styling trio's keys, for the same reason: their describe() branches on all of them.
            "format", "decimals", "currencySymbol", "sides", "style", "color",
            "horizontal", "vertical", "wrapText", "indent",
            // The two magnitude rules name their own colours and can hide the number.
            "minColor", "midColor", "maxColor", "showValue",
            // Outline and print keys: describe() branches on every one of them.
            "collapsed", "ungroup", "summaryBelow", "orientation", "fitToWidth", "fitToHeight",
            "printArea", "repeatHeaderRows", "repeatHeaderColumns", "paperSize", "printGridlines",
            // Links, notes and protection. "password" is here on purpose: the card must
            // never print it, and the only way to catch that is to feed one in.
            "address", "linkType", "author", "remove", "text", "password", "unlockedRange", "unprotect",
            "keyRange", "sourceColumn", "ifMissing", "groupBy", "valueColumn", "targetCell",
            "dataRange", "showMarkers",
            // The structural actions point with "at" and "count" rather than a range.
            "at", "function", "hasHeader", "type", "values", "min", "max", "style",
            // The option keys the TRANSFORM_COLUMN rules read: without these the rules' own
            // describe() methods are handed nothing and never really exercised.
            "length", "fill", "find", "replace", "pattern", "separator", "index");

    @Test
    void addFormula() {
        assertBothTenses(new AddFormulaHandler(),
                props("cell", "B10", "formula", "SUM(B2:B9)", "label", "Total"),
                "Add =SUM(B2:B9) in B10, labelled \"Total\"",
                "Added =SUM(B2:B9) in B10, labelled \"Total\"");
    }

    @Test
    void addFormulaKeepsASingleLeadingEquals() {
        assertBothTenses(new AddFormulaHandler(),
                props("cell", "C5", "formula", "=AVERAGE(C1:C4)", "sheetName", "Sales"),
                "Add =AVERAGE(C1:C4) in C5 on \"Sales\"",
                "Added =AVERAGE(C1:C4) in C5 on \"Sales\"");
    }

    @Test
    void addSheet() {
        assertBothTenses(new AddSheetHandler(), props("name", "Summary"),
                "Add a sheet named \"Summary\"",
                "Added a sheet named \"Summary\"");
    }

    @Test
    void clearCellsCollapsesASingleCellRange() {
        assertBothTenses(new ClearCellsHandler(), props("range", "B12:B12"),
                "Clear B12", "Cleared B12");
    }

    @Test
    void clearCellsStripsSheetPrefixAndAppendsSheet() {
        assertBothTenses(new ClearCellsHandler(), props("range", "Sales!A2:A20", "sheetName", "Sales"),
                "Clear A2:A20 on \"Sales\"",
                "Cleared A2:A20 on \"Sales\"");
    }

    @Test
    void conditionalFormatting() {
        assertBothTenses(new ConditionalFormattingHandler(),
                props("range", "C2:C20", "operator", ">", "value", "100", "backgroundColor", "#FEF08A"),
                "Highlight cells in C2:C20 where the value is greater than 100",
                "Highlighted cells in C2:C20 where the value is greater than 100");
    }

    @Test
    void conditionalFormattingSpellsOutEveryOperator() {
        assertThat(describeCondition(">=")).isEqualTo("Highlighted cells in C2:C20 where the value is at least 100");
        assertThat(describeCondition("<")).isEqualTo("Highlighted cells in C2:C20 where the value is less than 100");
        assertThat(describeCondition("<=")).isEqualTo("Highlighted cells in C2:C20 where the value is at most 100");
        assertThat(describeCondition("=")).isEqualTo("Highlighted cells in C2:C20 where the value is equal to 100");
        assertThat(describeCondition("!=")).isEqualTo("Highlighted cells in C2:C20 where the value is not equal to 100");
    }

    @Test
    void conditionalFormattingWithoutAComparisonFallsBackToTheNonEmptyRule() {
        assertBothTenses(new ConditionalFormattingHandler(), props("range", "C2:C20"),
                "Highlight non-empty cells in C2:C20",
                "Highlighted non-empty cells in C2:C20");
    }

    @Test
    void colorScaleNamesBothEndsOfTheScale() {
        assertBothTenses(new ColorScaleHandler(), props("range", "B2:B40"),
                "Shade B2:B40 by value, light red (#FECACA) for the lowest, light yellow (#FEF08A) in the middle, light green (#BBF7D0) for the highest",
                "Shaded B2:B40 by value, light red (#FECACA) for the lowest, light yellow (#FEF08A) in the middle, light green (#BBF7D0) for the highest");
    }

    @Test
    void colorScaleNamesAMiddleStopOnlyWhenThereIsOne() {
        assertBothTenses(new ColorScaleHandler(),
                props("range", "B2:B40", "minColor", "#FFFFFF", "midColor", "#DC2626", "maxColor", "#1E3A8A"),
                "Shade B2:B40 by value, white (#FFFFFF) for the lowest, red (#DC2626) in the middle, blue (#1E3A8A) for the highest",
                "Shaded B2:B40 by value, white (#FFFFFF) for the lowest, red (#DC2626) in the middle, blue (#1E3A8A) for the highest");
    }

    @Test
    void colorScaleReadsBackTheColoursThatWereAskedFor() {
        assertBothTenses(new ColorScaleHandler(),
                props("range", "B2:B40", "minColor", "#FFFFFF", "maxColor", "#1E3A8A", "sheetName", "Sales"),
                "Shade B2:B40 by value, white (#FFFFFF) for the lowest through blue (#1E3A8A) for the highest on \"Sales\"",
                "Shaded B2:B40 by value, white (#FFFFFF) for the lowest through blue (#1E3A8A) for the highest on \"Sales\"");
    }

    @Test
    void dataBars() {
        assertBothTenses(new DataBarsHandler(), props("range", "C2:C500"),
                "Draw sky blue (#0EA5E9) bars in C2:C500 in proportion to each value",
                "Drew sky blue (#0EA5E9) bars in C2:C500 in proportion to each value");
    }

    @Test
    void dataBarsSaysWhenTheNumbersAreHidden() {
        assertBothTenses(new DataBarsHandler(),
                props("range", "C2:C500", "color", "#15803D", "showValue", false),
                "Draw green (#15803D) bars in C2:C500 in proportion to each value, hiding the numbers themselves",
                "Drew green (#15803D) bars in C2:C500 in proportion to each value, hiding the numbers themselves");
    }

    @Test
    void groupRows() {
        assertBothTenses(new GroupRowsHandler(), props("range", "5:20"),
                "Group rows 5:20", "Grouped rows 5:20");
    }

    @Test
    void groupRowsSaysWhenTheyAreFoldedAway() {
        assertBothTenses(new GroupRowsHandler(), props("at", 5, "count", 16, "collapsed", true),
                "Group 16 rows from row 5 and fold them away",
                "Grouped 16 rows from row 5 and fold them away");
    }

    @Test
    void ungroupRowsReadsAsItsOwnAction() {
        assertBothTenses(new GroupRowsHandler(), props("range", "5:20", "ungroup", true),
                "Ungroup rows 5:20", "Ungrouped rows 5:20");
    }

    @Test
    void pageSetupSpellsOutEveryThingItSets() {
        assertBothTenses(new PageSetupHandler(),
                props("orientation", "landscape", "fitToWidth", 1, "repeatHeaderRows", "1:1"),
                "Set up the page to print sideways, fit 1 page across, however many down,"
                        + " repeat rows 1:1 on every page",
                "Set the page up to print sideways, fit 1 page across, however many down,"
                        + " repeat rows 1:1 on every page");
    }

    @Test
    void pageSetupNamesBothBoundsWhenBothAreGiven() {
        assertBothTenses(new PageSetupHandler(),
                props("fitToWidth", 1, "fitToHeight", 2, "printArea", "A1:D40", "sheetName", "Sales"),
                "Set up the page to fit onto 1 page across by 2 pages down, print only A1:D40 on \"Sales\"",
                "Set the page up to fit onto 1 page across by 2 pages down, print only A1:D40 on \"Sales\"");
    }

    @Test
    void hyperlink() {
        assertBothTenses(new HyperlinkHandler(),
                props("cell", "C1", "address", "https://example.com/report", "text", "Q1 report"),
                "Link C1 to https://example.com/report, showing \"Q1 report\"",
                "Linked C1 to https://example.com/report, showing \"Q1 report\"");
    }

    @Test
    void hyperlinkOverAColumnReadsAsTheOtherThingItIs() {
        assertBothTenses(new HyperlinkHandler(), props("range", "A2:A500"),
                "Make the addresses in A2:A500 clickable",
                "Made the addresses in A2:A500 clickable");
    }

    @Test
    void comment() {
        assertBothTenses(new CommentHandler(),
                props("cell", "B2", "text", "Checked against the invoice"),
                "Note on B2: \"Checked against the invoice\"",
                "Noted on B2: \"Checked against the invoice\"");
    }

    @Test
    void commentRemoval() {
        assertBothTenses(new CommentHandler(), props("cell", "B2", "remove", true),
                "Remove the note on B2", "Removed the note on B2");
    }

    @Test
    void protectSheet() {
        assertBothTenses(new ProtectSheetHandler(), props("unlockedRange", "B2:D100"),
                "Protect the sheet from edits, leaving B2:D100 editable",
                "Protected the sheet from edits, leaving B2:D100 editable");
    }

    @Test
    void protectSheetNeverPrintsThePassword() {
        String card = new ProtectSheetHandler().describe(
                props("password", "hunter2", "sheetName", "Sales"), StepTense.IMPERATIVE);

        assertThat(card)
                .as("a plan card is shown, logged and stored — a password must not travel with it")
                .doesNotContain("hunter2")
                .isEqualTo("Protect the sheet from edits, with a password on \"Sales\"");
    }

    @Test
    void lookupFromSheet() {
        assertBothTenses(new LookupFromSheetHandler(),
                props("range", "D2:D500", "keyRange", "A2:A500",
                        "sourceRange", "Products!A2:C100", "sourceColumn", "3"),
                "Fill D2:D500 from Products!A2:C100, column 3, matching on A2:A500",
                "Filled D2:D500 from Products!A2:C100, column 3, matching on A2:A500");
    }

    @Test
    void groupBy() {
        assertBothTenses(new GroupByHandler(),
                props("range", "A1:C500", "groupBy", "A", "valueColumn", "C",
                        "function", "sum", "targetSheet", "Summary"),
                "Summarise A1:C500 by column A, totalling column C, onto \"Summary\"",
                "Summarised A1:C500 by column A, totalling column C, onto \"Summary\"");
    }

    @Test
    void groupByCountNeedsNoValueColumn() {
        assertBothTenses(new GroupByHandler(),
                props("range", "A1:C500", "groupBy", "B", "function", "count"),
                "Summarise A1:C500 by column B, counting the rows",
                "Summarised A1:C500 by column B, counting the rows");
    }

    @Test
    void sparklines() {
        assertBothTenses(new SparklineHandler(),
                props("range", "F2:F13", "dataRange", "B2:E13"),
                "Draw a line sparkline in each of F2:F13 from B2:E13",
                "Drew a line sparkline in each of F2:F13 from B2:E13");
    }

    @Test
    void sparklinesNameTheirShape() {
        assertBothTenses(new SparklineHandler(),
                props("range", "F2:F13", "dataRange", "B2:E13", "type", "winLoss", "sheetName", "Sales"),
                "Draw a win/loss sparkline in each of F2:F13 from B2:E13 on \"Sales\"",
                "Drew a win/loss sparkline in each of F2:F13 from B2:E13 on \"Sales\"");
    }

    @Test
    void createChart() {
        assertBothTenses(new CreateChartHandler(),
                props("sourceRange", "A1:B12", "chartType", "barChart", "title", "Revenue by region"),
                "Create a bar chart \"Revenue by region\" from A1:B12",
                "Created a bar chart \"Revenue by region\" from A1:B12");
    }

    @Test
    void createPieChartOnANamedSheet() {
        assertBothTenses(new CreateChartHandler(),
                props("sourceRange", "A1:B6", "chartType", "pieChart", "title", "Share", "sheetName", "Sales"),
                "Create a pie chart \"Share\" from A1:B6 on \"Sales\"",
                "Created a pie chart \"Share\" from A1:B6 on \"Sales\"");
    }

    @Test
    void filterData() {
        assertBothTenses(new FilterDataHandler(), props("range", "A1:D1"),
                "Add a filter to A1:D1", "Added a filter to A1:D1");
    }

    @Test
    void formatCells() {
        assertBothTenses(new FormatCellsHandler(),
                props("range", "A1:E1", "backgroundColor", "#1E3A8A", "fontColor", "#FFFFFF", "bold", true),
                "Format A1:E1 — blue background (#1E3A8A), white text (#FFFFFF), bold",
                "Formatted A1:E1 — blue background (#1E3A8A), white text (#FFFFFF), bold");
    }

    @Test
    void formatCellsDropsAbsentStylingBits() {
        assertBothTenses(new FormatCellsHandler(), props("range", "A2:E20", "bold", false),
                "Format A2:E20", "Formatted A2:E20");
        assertBothTenses(new FormatCellsHandler(),
                props("range", "A1:A9", "backgroundColor", "#DC2626", "sheetName", "Sales"),
                "Format A1:A9 on \"Sales\" — red background (#DC2626)",
                "Formatted A1:A9 on \"Sales\" — red background (#DC2626)");
    }

    @Test
    void mergeCells() {
        assertBothTenses(new MergeCellsHandler(), props("range", "A1:C1"),
                "Merge A1:C1", "Merged A1:C1");
    }

    @Test
    void renameChartAxis() {
        assertBothTenses(new RenameChartAxisHandler(), props("axis", "value", "newTitle", "Revenue"),
                "Label the y-axis \"Revenue\"", "Labelled the y-axis \"Revenue\"");
        assertBothTenses(new RenameChartAxisHandler(), props("axis", "category", "newTitle", "Month"),
                "Label the x-axis \"Month\"", "Labelled the x-axis \"Month\"");
    }

    @Test
    void renameChartTitle() {
        assertBothTenses(new RenameChartTitleHandler(), props("newTitle", "Q3 revenue"),
                "Rename the chart title to \"Q3 revenue\"",
                "Renamed the chart title to \"Q3 revenue\"");
    }

    @Test
    void renameColumn() {
        assertBothTenses(new RenameColumnHandler(), props("cell", "B1", "newName", "Revenue"),
                "Rename the column in B1 to \"Revenue\"",
                "Renamed the column in B1 to \"Revenue\"");
    }

    @Test
    void renameSheet() {
        assertBothTenses(new RenameSheetHandler(), props("newName", "Q3"),
                "Rename the sheet to \"Q3\"", "Renamed the sheet to \"Q3\"");
        assertBothTenses(new RenameSheetHandler(), props("sheetName", "Sheet1", "newName", "Q3"),
                "Rename the sheet \"Sheet1\" to \"Q3\"",
                "Renamed the sheet \"Sheet1\" to \"Q3\"");
    }

    @Test
    void sortData() {
        assertBothTenses(new SortDataHandler(),
                props("range", "A2:D20", "columnIndex", 2, "ascending", false, "sheetName", "Sales"),
                "Sort A2:D20 by column C, highest first on \"Sales\"",
                "Sorted A2:D20 by column C, highest first on \"Sales\"");
    }

    @Test
    void sortDataDefaultsToAscending() {
        assertBothTenses(new SortDataHandler(), props("range", "A2:D20", "columnIndex", 0),
                "Sort A2:D20 by column A, lowest first",
                "Sorted A2:D20 by column A, lowest first");
    }

    @Test
    void sortDataAcceptsTheLooseTypesAnLlmProduces() {
        assertBothTenses(new SortDataHandler(), props("range", "A2:D20", "columnIndex", "2", "ascending", "false"),
                "Sort A2:D20 by column C, highest first",
                "Sorted A2:D20 by column C, highest first");
        assertBothTenses(new SortDataHandler(), props("range", "A2:D20", "columnIndex", 27.0),
                "Sort A2:D20 by column AB, lowest first",
                "Sorted A2:D20 by column AB, lowest first");
    }

    @Test
    void theArgumentLessFallbacksAreTenseAwareToo() {
        assertBothTenses(new SortDataHandler(), Map.of(), "Sort the data", "Sorted the data");
        assertBothTenses(new ClearCellsHandler(), Map.of(), "Clear the cells", "Cleared the cells");
        assertBothTenses(new MergeCellsHandler(), Map.of(), "Merge the cells", "Merged the cells");
        assertBothTenses(new FormatCellsHandler(), Map.of(), "Format the cells", "Formatted the cells");
        assertBothTenses(new AddSheetHandler(), Map.of(), "Add a sheet", "Added a sheet");
        assertBothTenses(new FilterDataHandler(), Map.of(), "Add a filter", "Added a filter");
        assertBothTenses(new AddFormulaHandler(), Map.of(), "Add a formula", "Added a formula");
        assertBothTenses(new CreateChartHandler(), Map.of(), "Create a chart", "Created a chart");
    }

    @ParameterizedTest
    @MethodSource("handlers")
    void describeSurvivesAnEmptyMap(ActionHandler handler) {
        assertSafe(handler, Map.of());
    }

    @ParameterizedTest
    @MethodSource("handlers")
    void describeSurvivesAnAllNullMap(ActionHandler handler) {
        Map<String, Object> nulls = new HashMap<>();
        ALL_KEYS.forEach(key -> nulls.put(key, null));
        assertSafe(handler, nulls);
    }

    @ParameterizedTest
    @MethodSource("handlers")
    void describeSurvivesWronglyTypedValues(ActionHandler handler) {
        Map<String, Object> junk = new HashMap<>();
        ALL_KEYS.forEach(key -> junk.put(key, 42));
        junk.put("columnIndex", "not a number");
        junk.put("bold", "yes");
        junk.put("backgroundColor", Map.of("color", "#15803D"));
        junk.put("value", new ArrayList<>());
        assertSafe(handler, junk);
    }

    @ParameterizedTest
    @MethodSource("handlers")
    void describeSurvivesANullMap(ActionHandler handler) {
        assertSafe(handler, null);
    }

    /**
     * The sweep above always leaves {@code operation} as junk, so {@code find()} returns null and no
     * rule's own {@code describe()} is ever reached. Naming a real operation per case is what puts
     * the rules themselves inside the guarantee — the registry catching {@code Exception} would not
     * contain an {@code Error}, and does nothing at all about wording.
     */
    @ParameterizedTest
    @MethodSource("transformOperations")
    void describeSurvivesGarbageOptionsForEveryTransformRule(String operation) {
        TransformColumnHandler handler = new TransformColumnHandler(TRANSFORMS);

        Map<String, Object> junk = new HashMap<>();
        ALL_KEYS.forEach(key -> junk.put(key, 42));
        junk.put("operation", operation);
        assertSafe(handler, junk);

        Map<String, Object> nulls = new HashMap<>();
        ALL_KEYS.forEach(key -> nulls.put(key, null));
        nulls.put("operation", operation);
        assertSafe(handler, nulls);
    }

    private static List<String> transformOperations() {
        return TRANSFORMS.operations();
    }

    /** Both tenses reach the UI, so both have to survive whatever the LLM put in the properties. */
    private static void assertSafe(ActionHandler handler, Map<String, Object> properties) {
        for (StepTense tense : StepTense.values()) {
            assertThatCode(() -> handler.describe(properties, tense)).doesNotThrowAnyException();
            assertThat(handler.describe(properties, tense))
                    .isNotBlank()
                    .doesNotContain("null")
                    .doesNotContain(handler.getType());
        }
        assertThat(handler.describe(properties)).isEqualTo(handler.describe(properties, StepTense.PAST));
    }

    private static void assertBothTenses(ActionHandler handler, Map<String, Object> properties,
                                         String imperative, String past) {
        assertThat(handler.describe(properties, StepTense.IMPERATIVE)).isEqualTo(imperative);
        assertThat(handler.describe(properties, StepTense.PAST)).isEqualTo(past);
        assertThat(handler.describe(properties)).isEqualTo(past);
    }

    private static String describeCondition(String operator) {
        return new ConditionalFormattingHandler().describe(props(
                "range", "C2:C20", "operator", operator, "value", "100"));
    }

    private static List<ActionHandler> handlers() {
        return HANDLERS;
    }

    private static Map<String, Object> props(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}
