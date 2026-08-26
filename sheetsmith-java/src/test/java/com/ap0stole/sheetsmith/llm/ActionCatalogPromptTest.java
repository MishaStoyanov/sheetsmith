package com.ap0stole.sheetsmith.llm;

import com.ap0stole.sheetsmith.services.excel.transform.ColumnTransformRegistry;
import com.ap0stole.sheetsmith.services.excel.transform.PhoneUsTransform;
import com.ap0stole.sheetsmith.services.excel.transform.ToNumberTransform;
import com.ap0stole.sheetsmith.services.excel.transform.TrimTransform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wiring that lets a transform bean reach both models. If this breaks, a rule exists in code
 * and is invisible to the only two callers that could ever ask for it.
 */
class ActionCatalogPromptTest {

    private final ActionCatalogPrompt catalog = new ActionCatalogPrompt(
            new ColumnTransformRegistry(List.of(new PhoneUsTransform(), new TrimTransform(), new ToNumberTransform())));

    @Test
    @DisplayName("every registered rule documents itself in the full catalog")
    void fullCatalogCarriesTheRules() {
        String prompt = catalog.mutatingActions();

        // Numbered last by hand, so it is asserted as "the last one" rather than as a literal that
        // has to be edited every time an action is added ahead of it.
        assertThat(prompt).containsPattern("(?m)^ *[0-9]+[.] TRANSFORM_COLUMN$");
        assertThat(prompt).contains("PHONE_US").contains("TRIM").contains("TO_NUMBER");
        assertThat(prompt).contains("+1 (555) 123-4567");
        // The 13 that were there before must survive the assembly.
        assertThat(prompt).contains("1. FORMAT_CELLS").contains("13. RENAME_CHART_AXIS");
    }

    /**
     * TRANSFORM_COLUMN is numbered by hand in its own constant and appended after the rest, so an
     * action added to {@code MUTATING_ACTIONS} silently collides with it — the numbers read fine in
     * each constant and wrongly in the string the model is actually sent.
     */
    @Test
    @DisplayName("the composed catalog numbers its entries 1..N with no gap or repeat")
    void entriesAreNumberedConsecutively() {
        Matcher matcher = Pattern.compile("(?m)^\\s*(\\d+)\\. ([A-Z_]+)$").matcher(catalog.mutatingActions());

        List<Integer> numbers = new ArrayList<>();
        while (matcher.find()) {
            numbers.add(Integer.parseInt(matcher.group(1)));
        }

        assertThat(numbers).as("the catalog documents at least the actions it started with")
                .hasSizeGreaterThanOrEqualTo(13);
        assertThat(numbers)
                .as("1..N with no gap and no repeat — the count itself is nobody's to maintain")
                .containsExactlyElementsOf(IntStream.rangeClosed(1, numbers.size()).boxed().toList());
    }

    @Test
    @DisplayName("a new action is useless unless it reaches BOTH tiers, so check them together")
    void newActionsAreInTheFullCatalogAndTheIndex() {
        for (String action : List.of("SET_CELL_VALUE", "AUTOSIZE_COLUMNS", "FREEZE_PANES",
                "NUMBER_FORMAT", "SET_BORDERS", "ALIGN_CELLS",
                "INSERT_ROWS", "DELETE_ROWS", "INSERT_COLUMNS", "DELETE_COLUMNS",
                "FILL_FORMULA", "ADD_TOTALS_ROW", "REMOVE_DUPLICATES",
                "DELETE_SHEET", "UNMERGE_CELLS", "DATA_VALIDATION", "CREATE_TABLE")) {
            assertThat(catalog.mutatingActions()).as("%s full entry", action).contains(action);
            assertThat(catalog.mutatingActionsIndex()).as("%s index line", action).contains(action);
        }
    }

    @Test
    @DisplayName("the index stays one line per action — the compact tier is what makes it affordable")
    void theIndexIsOneLinePerAction() {
        List<String> lines = catalog.mutatingActionsIndex().lines()
                .filter(line -> line.contains(" — "))
                .toList();

        long fullEntries = Pattern.compile("(?m)^ *[0-9]+[.] [A-Z_]+$")
                .matcher(catalog.mutatingActions()).results().count();

        assertThat(lines)
                .as("an action in the full catalog but not the index is invisible to the chat")
                .hasSize((int) fullEntries);
        assertThat(lines).allSatisfy(line -> assertThat(line.length()).isLessThan(140));
    }

    /**
     * Asserted as fragments, not as whole sentences: pinning the exact wording would break the build
     * on a legitimate reword, while dropping the test would let a tidy-up delete a rule the model
     * cannot infer. What must survive is the semantic core of each.
     */
    @Test
    @DisplayName("the rules a model cannot guess are stated somewhere in the catalog")
    void spellsOutTheEasilyConfusedRules() {
        String prompt = catalog.mutatingActions();

        // A quoted number that does not round-trip stays text.
        assertThat(prompt).contains("42.0").containsIgnoringCase("text");
        // Writing over a formula destroys it.
        assertThat(prompt).containsIgnoringCase("formula");
        // Counts rather than indices, and the way back.
        assertThat(prompt).containsIgnoringCase("counts").containsIgnoringCase("unfreez");
        // A column range, not a row range.
        assertThat(prompt).containsIgnoringCase("columns");
    }

    @Test
    @DisplayName("the compact index names the operations without spelling out their rules")
    void indexNamesTheOperations() {
        String index = catalog.mutatingActionsIndex();

        assertThat(index).contains("TRANSFORM_COLUMN — range (ONE column), operation (PHONE_US|TO_NUMBER|TRIM)");
        assertThat(index).contains("FORMAT_CELLS");
        assertThat(index).contains("Every action also accepts");
        assertThat(index).doesNotContain("+1 (555) 123-4567");
        assertThat(index.length()).isLessThan(catalog.mutatingActions().length() / 2);
    }

    @Test
    @DisplayName("the catalog says which action changes values, because FORMAT_CELLS looks like it might")
    void steersAwayFromFormatCells() {
        assertThat(catalog.mutatingActions())
                .contains("FORMAT_CELLS only changes how");
    }
}
