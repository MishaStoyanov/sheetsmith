package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.configs.ChatConfig;
import com.ap0stole.sheetsmith.domain.dto.ExcelSchemaDto;
import com.ap0stole.sheetsmith.domain.dto.SheetSchemaDto;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The schema is the only thing about a sheet the LLM ever sees, so it has to survive the shapes
 * real spreadsheets come in — starting with a header row that has holes in it.
 */
class SchemaExtractorServiceTest {

    private SchemaExtractorService service;
    private Path tempDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        this.service = new SchemaExtractorService(new ChatConfig());
        this.tempDir = tempDir;
    }

    @Test
    @DisplayName("a gap in the header row does not blow up extraction")
    void survivesAGapInTheHeaderRow() throws Exception {
        Path file = write(sheet -> {
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Product");
            // column B deliberately left empty
            header.createCell(2).setCellValue("Revenue");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("Widget A");
            data.createCell(2).setCellValue(1240);
        });

        ExcelSchemaDto schema = service.extract(file.toString());

        SheetSchemaDto first = schema.getSheets().getFirst();
        assertThat(first.getColumns()).hasSize(3);
        assertThat(first.getColumns().get(0)).isEqualTo("Product");
        assertThat(first.getColumns().get(2)).isEqualTo("Revenue");
    }

    @Test
    @DisplayName("the placeholder keeps columns positional, so a column index still means something")
    void namesTheGapWithoutShiftingTheOthers() throws Exception {
        Path file = write(sheet -> {
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Product");
            header.createCell(2).setCellValue("Revenue");
            sheet.createRow(1).createCell(0).setCellValue("Widget A");
        });

        SheetSchemaDto first = service.extract(file.toString()).getSheets().getFirst();

        // Revenue must stay at index 2 — it is column C, and the model derives columnIndex from here.
        assertThat(first.getColumns().get(1)).contains("B");
        assertThat(first.getColumns().indexOf("Revenue")).isEqualTo(2);
        assertThat(first.getHeaderRange()).isEqualTo("A1:C1");
    }

    @Test
    @DisplayName("a blank header cell is treated the same as a missing one")
    void treatsBlankHeadersAsUnnamed() throws Exception {
        Path file = write(sheet -> {
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Product");
            header.createCell(1).setCellValue("   ");
            header.createCell(2).setCellValue("Revenue");
            sheet.createRow(1).createCell(0).setCellValue("Widget A");
        });

        SheetSchemaDto first = service.extract(file.toString()).getSheets().getFirst();

        assertThat(first.getColumns().get(1)).isNotBlank().doesNotContain("null");
        assertThat(first.getColumns()).noneMatch(String::isBlank);
    }

    // ── The structure-only guarantee ─────────────────────────────────────────

    @Test
    @DisplayName("with the chat off, the schema carries no value read out of a data cell")
    void sendsOnlyStructureWhenTheChatIsOff() throws Exception {
        Path file = write(sheet -> {
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Product");
            header.createCell(1).setCellValue("Revenue");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("Acme Corp confidential");
            data.createCell(1).setCellValue(1240);
            Row totals = sheet.createRow(2);
            totals.createCell(0).setCellValue("Quarterly total");
            totals.createCell(1).setCellFormula("SUM(B2:B2)");
        });

        ChatConfig off = new ChatConfig();
        off.setEnabled(false);
        SheetSchemaDto strict = new SchemaExtractorService(off)
                .extract(file.toString()).getSheets().getFirst();

        // Everything the planner is given, as one string — the same thing that ends up in the prompt.
        String everything = strict.getSheetName() + strict.getHeaderRange() + strict.getDataRange()
                + strict.getColumns() + strict.getExistingFormulas();

        assertThat(everything)
                .as("a label is read out of a cell, so it is not structure and must not be sent")
                .doesNotContain("Quarterly total")
                .doesNotContain("Acme Corp confidential");
        assertThat(strict.getColumns()).containsExactly("Product", "Revenue");
        assertThat(strict.getExistingFormulas())
                .as("the formula itself is structure, and the planner needs it to avoid duplicates")
                .anyMatch(entry -> entry.contains("SUM(B2:B2)"));
    }

    @Test
    @DisplayName("with the chat on, the label comes back — it is what tells two totals apart")
    void theLabelIsSentWhenTheChatIsOn() throws Exception {
        Path file = write(sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("Product");
            Row totals = sheet.createRow(1);
            totals.createCell(0).setCellValue("Quarterly total");
            totals.createCell(1).setCellFormula("SUM(A1:A1)");
        });

        SheetSchemaDto normal = service.extract(file.toString()).getSheets().getFirst();

        assertThat(normal.getExistingFormulas())
                .anyMatch(entry -> entry.contains("Quarterly total"));
    }

    private Path write(java.util.function.Consumer<XSSFSheet> build) throws Exception {
        Path file = tempDir.resolve("sheet.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             FileOutputStream out = new FileOutputStream(file.toFile())) {
            build.accept(workbook.createSheet("Sales"));
            workbook.write(out);
        }
        return file;
    }
}
