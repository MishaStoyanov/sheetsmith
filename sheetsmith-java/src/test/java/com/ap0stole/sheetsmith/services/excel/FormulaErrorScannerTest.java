package com.ap0stole.sheetsmith.services.excel;

import com.ap0stole.sheetsmith.services.excel.FormulaErrorScanner.CellError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The scanner runs against the very workbook that is about to be written to disk, so "found the
 * error" and "changed nothing" matter equally here.
 */
class FormulaErrorScannerTest {

    private final FormulaErrorScanner scanner = new FormulaErrorScanner();

    @Test
    @DisplayName("a formula dividing by zero is reported with its sheet and A1 reference")
    void findsDivideByZero() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("Sales");
            Row row = sheet.createRow(3);
            row.createCell(0).setCellValue(10);
            row.createCell(1).setCellValue(0);
            row.createCell(2).setCellFormula("A4/B4");

            assertThat(scanner.scan(workbook)).singleElement().satisfies(error -> {
                assertThat(error.sheet()).isEqualTo("Sales");
                assertThat(error.reference()).isEqualTo("C4");
                assertThat(error.error()).isEqualTo("#DIV/0!");
                assertThat(error.label()).isEqualTo("Sales!C4 #DIV/0!");
            });
        }
    }

    @Test
    @DisplayName("a healthy sheet reports nothing")
    void findsNothingWhenHealthy() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("Sales");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(10);
            row.createCell(1).setCellValue(4);
            row.createCell(2).setCellFormula("A1/B1");

            assertThat(scanner.scan(workbook)).isEmpty();
        }
    }

    @Test
    @DisplayName("scanning does not modify the workbook")
    void leavesTheWorkbookUntouched() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("Sales");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(1);
            row.createCell(1).setCellValue(0);
            row.createCell(2).setCellFormula("A1/B1");
            sheet.createRow(4).createCell(0).setCellValue("tail");

            List<String> before = shape(sheet);
            String formula = sheet.getRow(0).getCell(2).getCellFormula();

            assertThat(scanner.scan(workbook)).isNotEmpty();

            assertThat(shape(sheet)).isEqualTo(before);
            assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(2);
            assertThat(sheet.getLastRowNum()).isEqualTo(4);
            assertThat(sheet.getRow(0).getPhysicalNumberOfCells()).isEqualTo(3);
            assertThat(sheet.getRow(0).getCell(2).getCellFormula()).isEqualTo(formula);
        }
    }

    @Test
    @DisplayName("gaps in rows and columns are skipped rather than dereferenced")
    void toleratesSparseSheets() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("Sparse");
            sheet.createRow(0).createCell(4).setCellValue(0);
            sheet.createRow(9).createCell(7).setCellFormula("E1/E1");
            sheet.createRow(20);

            assertThat(scanner.scan(workbook)).singleElement()
                    .satisfies(error -> assertThat(error.reference()).isEqualTo("H10"));
        }
    }

    @Test
    @DisplayName("errors are looked for on every sheet")
    void scansEverySheet() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("First").createRow(0).createCell(0).setCellValue(5);
            workbook.createSheet("Second").createRow(1).createCell(1).setCellFormula("1/0");

            assertThat(scanner.scan(workbook))
                    .extracting(CellError::sheet, CellError::reference)
                    .containsExactly(tuple("Second", "B2"));
        }
    }

    @Test
    @DisplayName("a broken fill is capped instead of flooding the model")
    void capsTheNumberOfReportedCells() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("Sales");
            for (int i = 0; i < 40; i++) {
                sheet.createRow(i).createCell(0).setCellFormula("1/0");
            }

            assertThat(scanner.scan(workbook)).hasSize(20);
        }
    }

    @Test
    @DisplayName("only errors absent before the edit count as new")
    void diffsAgainstTheBaseline() {
        CellError old = new CellError("Sales", "B2", "#DIV/0!");
        CellError fresh = new CellError("Sales", "C5", "#REF!");

        assertThat(FormulaErrorScanner.newErrors(List.of(old), List.of(old, fresh)))
                .containsExactly(fresh);
        assertThat(FormulaErrorScanner.newErrors(List.of(old), List.of(old))).isEmpty();
        assertThat(FormulaErrorScanner.newErrors(null, List.of(fresh))).containsExactly(fresh);
        assertThat(FormulaErrorScanner.newErrors(List.of(old), List.of())).isEmpty();
    }

    /** Row/cell census used to prove the scan left no residue. */
    private List<String> shape(XSSFSheet sheet) {
        List<String> shape = new ArrayList<>();
        for (Row row : sheet) {
            shape.add(row.getRowNum() + ":" + row.getPhysicalNumberOfCells() + ":" + row.getLastCellNum());
        }
        return shape;
    }
}
