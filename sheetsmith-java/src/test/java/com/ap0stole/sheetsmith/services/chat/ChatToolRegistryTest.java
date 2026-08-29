package com.ap0stole.sheetsmith.services.chat;

import com.ap0stole.sheetsmith.llm.ActionCatalogPrompt;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.ActionRegistry;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.ap0stole.sheetsmith.services.excel.query.QueryResult;
import com.ap0stole.sheetsmith.services.excel.query.QueryTool;
import com.ap0stole.sheetsmith.services.excel.transform.ColumnTransformRegistry;
import com.ap0stole.sheetsmith.services.excel.transform.PhoneUsTransform;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registry is what makes an action usable from chat the moment it exists as a bean, so the
 * tests here are about routing and about failures staying inside the turn.
 */
class ChatToolRegistryTest {

    private ChatToolRegistry registry;
    private XSSFWorkbook workbook;

    @BeforeEach
    void setUp() {
        registry = new ChatToolRegistry(
                new ActionRegistry(List.of(new StubAction(), new ExplodingAction())),
                new ActionCatalogPrompt(new ColumnTransformRegistry(List.of(new PhoneUsTransform()))),
                List.of(new StubQuery()));
        workbook = new XSSFWorkbook();
    }

    @Test
    @DisplayName("a query is routed read-only and narrated by the tool itself")
    void routesQueries() {
        ToolInvocation invocation = registry.invoke(workbook, "stub_query", Map.of("range", "A1:B2"));

        assertThat(invocation.success()).isTrue();
        assertThat(invocation.mutating()).isFalse();
        assertThat(invocation.humanText()).isEqualTo("Looked at A1:B2");
        assertThat(invocation.data()).isEqualTo(Map.of("value", 42));
    }

    @Test
    @DisplayName("an action is routed as mutating")
    void routesActions() {
        ToolInvocation invocation = registry.invoke(workbook, "STUB_ACTION", Map.of());

        assertThat(invocation.mutating()).isTrue();
        assertThat(invocation.success()).isTrue();
        assertThat(registry.isMutating("STUB_ACTION")).isTrue();
        assertThat(registry.isMutating("STUB_QUERY")).isFalse();
    }

    @Test
    @DisplayName("a hallucinated tool name comes back as a correctable error, not an exception")
    void reportsUnknownTools() {
        ToolInvocation invocation = registry.invoke(workbook, "MAKE_COFFEE", Map.of());

        assertThat(invocation.success()).isFalse();
        assertThat(invocation.error()).contains("Unknown tool 'MAKE_COFFEE'").contains("STUB_ACTION");
        assertThat(registry.isKnown("MAKE_COFFEE")).isFalse();
    }

    @Test
    @DisplayName("a tool that throws fails the step, not the turn")
    void containsToolFailures() {
        ToolInvocation invocation = registry.invoke(workbook, "EXPLODING_ACTION", Map.of());

        assertThat(invocation.success()).isFalse();
        assertThat(invocation.error()).isEqualTo("range is not a range");
        assertThat(invocation.mutating()).isTrue();
    }

    @Test
    @DisplayName("the prompt catalog carries both families, queries numbered after the actions")
    void buildsPromptCatalog() {
        String catalog = registry.toolCatalogPrompt(true);

        assertThat(catalog)
                .contains("ACTION TOOLS").contains("FORMAT_CELLS")
                .contains("QUERY TOOLS").contains("3. STUB_QUERY");
    }

    @Test
    @DisplayName("the compact catalog still names every action but drops the editing rules")
    void compactCatalogIsSubstantiallySmaller() {
        String full = registry.toolCatalogPrompt(true);
        String compact = registry.toolCatalogPrompt(false);

        // Every action must still be pickable, and the query specs are untouched.
        assertThat(compact).contains("FORMAT_CELLS").contains("RENAME_CHART_AXIS").contains("3. STUB_QUERY");
        // The bulk — per-action prose and the colour table — is what a question turn should not pay for.
        assertThat(compact).doesNotContain("COLOR REFERENCE");
        assertThat(compact.length()).isLessThan(full.length() / 2);
    }

    private static class StubAction implements ActionHandler {
        @Override
        public String getType() {
            return "STUB_ACTION";
        }

        @Override
        public String execute(XSSFWorkbook workbook, Map<String, Object> properties) {
            // nothing to do — routing is what is under test
            return null;
        }

        @Override
        public String describe(Map<String, Object> properties, StepTense tense) {
            return "Did the stub thing";
        }
    }

    private static class ExplodingAction implements ActionHandler {
        @Override
        public String getType() {
            return "EXPLODING_ACTION";
        }

        @Override
        public String execute(XSSFWorkbook workbook, Map<String, Object> properties) {
            throw new IllegalArgumentException("range is not a range");
        }
    }

    private static class StubQuery implements QueryTool {
        @Override
        public String getType() {
            return "STUB_QUERY";
        }

        @Override
        public String promptSpec() {
            return "STUB_QUERY\n   Keys: \"range\"";
        }

        @Override
        public QueryResult execute(XSSFWorkbook workbook, Map<String, Object> properties) {
            return new QueryResult("42", Map.of("value", 42));
        }

        @Override
        public String describe(Map<String, Object> properties, StepTense tense) {
            return "Looked at " + properties.get("range");
        }
    }
}
