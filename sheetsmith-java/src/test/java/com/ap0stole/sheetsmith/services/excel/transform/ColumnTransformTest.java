package com.ap0stole.sheetsmith.services.excel.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rules themselves, away from any workbook. The phone cases are the eight spellings that
 * actually occur in a 35 000-row export, which is the whole reason this action exists.
 */
class ColumnTransformTest {

    private static final Map<String, Object> NO_OPTIONS = Map.of();

    // ── PHONE_US ──────────────────────────────────────────────────────────────

    @ParameterizedTest
    @DisplayName("every US phone spelling in the wild collapses to one format")
    @ValueSource(strings = {
            "938-964-6770",
            "1-938-964-6770",
            "(938) 964-6770",
            "+1-938-964-6770",
            "938.964.6770",
            "+1.938.964.6770",
            "+1 (938) 964-6770",
            "19389646770",
            "9389646770",
    })
    void phoneShapesAllNormalise(String raw) {
        assertThat(new PhoneUsTransform().apply(raw, NO_OPTIONS)).contains("+1 (938) 964-6770");
    }

    @Test
    @DisplayName("a phone stored as a number normalises like any other, not as 1.9389646770E10")
    void phoneStoredAsNumber() {
        // What CellValues hands over for a numeric cell holding 19389646770.
        assertThat(new PhoneUsTransform().apply("19389646770", NO_OPTIONS)).contains("+1 (938) 964-6770");
    }

    @ParameterizedTest
    @DisplayName("anything that is not a ten-digit number is left alone rather than mangled")
    @ValueSource(strings = {"", "not a phone", "555-1234", "+44 20 7946 0958", "1234567890123"})
    void phoneLeavesUnconvertibleValuesAlone(String raw) {
        assertThat(new PhoneUsTransform().apply(raw, NO_OPTIONS)).isEmpty();
    }

    // ── Text tidying ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("TRIM flattens the line breaks Excel hides inside a cell")
    void trimCollapsesWhitespace() {
        assertThat(new TrimTransform().apply("  2070 Vicente Points\nDaVille,  CA  ", NO_OPTIONS))
                .contains("2070 Vicente Points DaVille, CA");
    }

    @Test
    void upperAndLower() {
        assertThat(new UpperCaseTransform().apply("mixed Case", NO_OPTIONS)).contains("MIXED CASE");
        assertThat(new LowerCaseTransform().apply("Maida54@GMAIL.com", NO_OPTIONS)).contains("maida54@gmail.com");
    }

    @ParameterizedTest
    @DisplayName("title case respects the punctuation real names carry")
    @CsvSource({
            "'JOANIE CASPER','Joanie Casper'",
            "'lessie o''reilly','Lessie O''Reilly'",
            "'JEAN-LUC PICARD','Jean-Luc Picard'",
    })
    void titleCase(String raw, String expected) {
        assertThat(new TitleCaseTransform().apply(raw, NO_OPTIONS)).contains(expected);
    }

    @Test
    @DisplayName("DIGITS_ONLY keeps every digit — dropping a country code is PHONE_US's job, not its")
    void digitsOnly() {
        assertThat(new DigitsOnlyTransform().apply("+1 (938) 964-6770", NO_OPTIONS)).contains("19389646770");
        assertThat(new DigitsOnlyTransform().apply("no digits here", NO_OPTIONS)).isEmpty();
    }

    // ── Rules with arguments ──────────────────────────────────────────────────

    @Test
    void replaceIsLiteralNotAPattern() {
        Map<String, Object> options = Map.of("find", ".", "replace", "-");
        assertThat(new ReplaceTransform().apply("938.964.6770", options)).contains("938-964-6770");
    }

    @Test
    void replaceWithoutAFindArgumentSaysSo() {
        var replace = new ReplaceTransform();
        Map<String, Object> noOptions = Map.of();
        assertThatThrownBy(() -> replace.apply("x", noOptions))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\"find\"");
    }

    @Test
    void regexReplaceSupportsGroups() {
        Map<String, Object> options = Map.of("pattern", "(\\d{3})(\\d{3})(\\d{4})", "replacement", "$1-$2-$3");
        assertThat(new RegexReplaceTransform().apply("9389646770", options)).contains("938-964-6770");
    }

    @Test
    @DisplayName("a broken pattern fails the step instead of skipping all 35 000 rows")
    void regexReplaceRejectsABrokenPattern() {
        var regexReplace = new RegexReplaceTransform();
        Map<String, Object> brokenPattern = Map.of("pattern", "([unclosed");
        assertThatThrownBy(() -> regexReplace.apply("x", brokenPattern))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a valid regular expression");
    }

    @Test
    @DisplayName("SPLIT_TAKE understands the \\n a model writes for an in-cell line break")
    void splitTakeOnAnEscapedNewline() {
        String address = "2070 Vicente Points\nDaVille, CA 90210";
        assertThat(new SplitTakeTransform().apply(address, Map.of("separator", "\\n")))
                .contains("2070 Vicente Points");
        assertThat(new SplitTakeTransform().apply(address, Map.of("separator", "\\n", "index", -1)))
                .contains("DaVille, CA 90210");
    }

    @Test
    void splitTakeLeavesValuesWithTooFewPiecesAlone() {
        assertThat(new SplitTakeTransform().apply("one piece only", Map.of("separator", "\\n", "index", 3)))
                .isEmpty();
    }

    @Test
    void padLeftRestoresLeadingZeros() {
        assertThat(new PadLeftTransform().apply("451", Map.of("length", 5))).contains("00451");
        assertThat(new PadLeftTransform().apply("902101", Map.of("length", 5))).contains("902101");
    }

    @ParameterizedTest
    @DisplayName("numbers saved as text come back as numbers, whichever decimal convention wrote them")
    @CsvSource({
            "'$1,234.56','1234.56'",
            "'1.234,56','1234.56'",
            "'1 234','1234.0'",
            "'42','42.0'",
            "'1,5','1.5'",
    })
    void toNumber(String raw, String expected) {
        assertThat(new ToNumberTransform().apply(raw, NO_OPTIONS)).contains(expected);
    }

    @Test
    void toNumberReadsAccountingNegatives() {
        assertThat(new ToNumberTransform().apply("(500)", NO_OPTIONS)).contains("-500.0");
        assertThat(new ToNumberTransform().apply("not a number", NO_OPTIONS)).isEmpty();
    }

    // ── Registry ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a transform bean documents itself into the prompt just by existing")
    void registryBuildsThePromptFromTheBeans() {
        ColumnTransformRegistry registry = new ColumnTransformRegistry(
                List.of(new PhoneUsTransform(), new TrimTransform()));

        assertThat(registry.operations()).containsExactly("PHONE_US", "TRIM");
        assertThat(registry.promptBlock()).contains("PHONE_US").contains("TRIM");
        assertThat(registry.find("phone_us")).isInstanceOf(PhoneUsTransform.class);
        assertThat(registry.find("NOPE")).isNull();
    }

    @Test
    void registryDescribesAnUnknownOperationWithoutThrowing() {
        ColumnTransformRegistry registry = new ColumnTransformRegistry(List.of(new PhoneUsTransform()));

        assertThat(registry.describe("MYSTERY_RULE", Map.of())).isEqualTo("mystery rule");
        assertThat(registry.describe("PHONE_US", Map.of())).contains("+1 (XXX) XXX-XXXX");
    }

    @Test
    @DisplayName("a null value is never rewritten by any rule")
    void nullIsAlwaysLeftAlone() {
        List<ColumnTransform> all = List.of(new PhoneUsTransform(), new TrimTransform(),
                new UpperCaseTransform(), new LowerCaseTransform(), new TitleCaseTransform(),
                new DigitsOnlyTransform(), new ReplaceTransform(), new RegexReplaceTransform(),
                new SplitTakeTransform(), new PadLeftTransform(), new ToNumberTransform());

        for (ColumnTransform transform : all) {
            Optional<String> result = transform.apply(null, Map.of("find", "a", "pattern", "a",
                    "separator", ",", "length", 3));
            assertThat(result).as(transform.getType()).isEmpty();
        }
    }
}
