package com.ap0stole.sheetsmith.services.excel;

import com.ap0stole.sheetsmith.configs.ProcessingConfig;
import com.ap0stole.sheetsmith.llm.ActionCatalogPrompt;
import com.ap0stole.sheetsmith.services.excel.transform.ColumnTransformRegistry;
import com.ap0stole.sheetsmith.services.excel.actions.AlignCellsHandler;
import com.ap0stole.sheetsmith.services.excel.actions.AutosizeColumnsHandler;
import com.ap0stole.sheetsmith.services.excel.actions.FreezePanesHandler;
import com.ap0stole.sheetsmith.services.excel.actions.NumberFormatHandler;
import com.ap0stole.sheetsmith.services.excel.actions.SetBordersHandler;
import com.ap0stole.sheetsmith.services.excel.actions.SetCellValueHandler;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * That the handlers are actually beans, and that the one budget they read actually binds.
 * <p>
 * {@code @Component} is the entire registration mechanism here — no list to add to — which makes it
 * the entire single point of failure, and every other test in this batch constructs its handler by
 * hand and so cannot see a wiring mistake. The only test that boots a context is
 * {@code SheetSmithApplicationTests}, and it needs Docker, so it is skipped wherever Docker is
 * not running: without this class, a missing annotation or an unsatisfiable constructor would first
 * be noticed in production.
 * <p>
 * {@link ApplicationContextRunner} builds a real context from just the two packages that matter, so
 * there is no database, no Testcontainers, and nothing to skip.
 */
class ActionWiringTest {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(ExcelActionSlice.class);

    /**
     * Component scanning is the point, not a convenience: importing the handlers explicitly would
     * prove they can be constructed, which is not the thing in doubt.
     */
    @Configuration
    @EnableConfigurationProperties(ProcessingConfig.class)
    @Import(ActionRegistry.class)
    @ComponentScan(basePackages = {
            "com.ap0stole.sheetsmith.services.excel.actions",
            "com.ap0stole.sheetsmith.services.excel.transform"})
    static class ExcelActionSlice {
    }

    @Test
    @DisplayName("the new styling handlers are discovered and resolve by their action type strings")
    void theNewHandlersAreRegistered() {
        contexts.run(context -> {
            assertThat(context).hasNotFailed();
            ActionRegistry registry = context.getBean(ActionRegistry.class);

            assertThat(registry.find("SET_CELL_VALUE")).isInstanceOf(SetCellValueHandler.class);
            assertThat(registry.find("AUTOSIZE_COLUMNS")).isInstanceOf(AutosizeColumnsHandler.class);
            assertThat(registry.find("FREEZE_PANES")).isInstanceOf(FreezePanesHandler.class);
            assertThat(registry.find("NUMBER_FORMAT")).isInstanceOf(NumberFormatHandler.class);
            assertThat(registry.find("SET_BORDERS")).isInstanceOf(SetBordersHandler.class);
            assertThat(registry.find("ALIGN_CELLS")).isInstanceOf(AlignCellsHandler.class);
        });
    }

    @Test
    @DisplayName("the registry holds exactly the actions the catalog documents — no hand-kept count")
    void theRegistryMatchesTheDocumentedCount() {
        contexts.run(context -> {
            ActionRegistry registry = context.getBean(ActionRegistry.class);

            // The two sources of truth check each other rather than a number written down a third
            // time: a handler that fails to register, or an entry added to the catalog without one,
            // both show up here without anybody remembering to bump a literal.
            long documented = new ActionCatalogPrompt(new ColumnTransformRegistry(List.of()))
                    .mutatingActionsIndex().lines()
                    .filter(line -> line.contains(" — "))
                    .count();

            assertThat(registry.size())
                    .as("every action the prompt documents has to be a bean, and vice versa")
                    .isEqualTo((int) documented);
            assertThat(registry.types())
                    .contains("SET_CELL_VALUE", "AUTOSIZE_COLUMNS", "FREEZE_PANES", "TRANSFORM_COLUMN",
                            "NUMBER_FORMAT", "SET_BORDERS", "ALIGN_CELLS",
                            "INSERT_ROWS", "DELETE_ROWS", "INSERT_COLUMNS", "DELETE_COLUMNS",
                            "FILL_FORMULA", "ADD_TOTALS_ROW", "REMOVE_DUPLICATES",
                            "DELETE_SHEET", "UNMERGE_CELLS", "DATA_VALIDATION", "CREATE_TABLE");
        });
    }

    @Test
    @DisplayName("a handler taking a constructor argument still resolves — the untested edge of the three")
    void theHandlerWithADependencyIsSatisfiable() {
        contexts.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AutosizeColumnsHandler.class);
            assertThat(context).hasSingleBean(ProcessingConfig.class);
        });
    }

    @Test
    @DisplayName("max-autosize-cells binds from the property, and its default matches application.yaml")
    void theAutosizeBudgetBinds() {
        contexts.run(context ->
                assertThat(context.getBean(ProcessingConfig.class).getMaxAutosizeCells())
                        .as("the Java default has to agree with the yaml default, or one of them is a lie")
                        .isEqualTo(500_000));

        contexts.withPropertyValues("xlsxai.processing.max-autosize-cells=1234").run(context ->
                assertThat(context.getBean(ProcessingConfig.class).getMaxAutosizeCells()).isEqualTo(1234));

        // The exact expression application.yaml uses, so the placeholder and its default are covered.
        contexts.withPropertyValues(
                        "xlsxai.processing.max-autosize-cells=${XLSXAI_MAX_AUTOSIZE_CELLS:500000}")
                .run(context -> assertThat(context.getBean(ProcessingConfig.class).getMaxAutosizeCells())
                        .isEqualTo(500_000));
    }

    @Test
    @DisplayName("the injected config is the bound one, proven by making the budget bite")
    void theBoundBudgetReachesTheHandler() {
        // A budget of 1 cannot afford a single column of a two-row sheet, so the step has to report
        // the column as unmeasured. A bean holding a default-constructed ProcessingConfig — 500 000 —
        // would size it instead, which is what makes this an assertion about the injected value.
        contexts.withPropertyValues("xlsxai.processing.max-autosize-cells=1").run(context ->
                assertThat(sizeOneColumn(context.getBean(AutosizeColumnsHandler.class)))
                        .contains("not measured"));

        contexts.withPropertyValues("xlsxai.processing.max-autosize-cells=500000").run(context ->
                assertThat(sizeOneColumn(context.getBean(AutosizeColumnsHandler.class)))
                        .doesNotContain("not measured"));
    }

    private String sizeOneColumn(AutosizeColumnsHandler handler) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("Worksheet");
            sheet.createRow(0).createCell(0).setCellValue("something reasonably long");
            sheet.createRow(1).createCell(0).setCellValue("and more of it");

            Map<String, Object> properties = new HashMap<>();
            properties.put("range", "A:A");
            return handler.execute(workbook, properties);
        }
    }
}
