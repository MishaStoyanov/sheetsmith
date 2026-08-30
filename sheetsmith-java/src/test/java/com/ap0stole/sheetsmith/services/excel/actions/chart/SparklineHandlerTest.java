package com.ap0stole.sheetsmith.services.excel.actions.chart;

import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sparklines are hand-written x14 XML — POI 5.5.1 has no API and no schema classes for them — so
 * nothing validates the shape on the way out and these tests can only prove the bytes are where we
 * put them and that they survive a save. Whether <em>Excel</em> accepts them was answered separately,
 * by having Excel open and re-save the file; see the note in ARCHITECTURE.md. Change the XML and that check
 * has to be run again.
 */
class SparklineHandlerTest {

    @TempDir
    Path tempDir;

    private XSSFWorkbook workbook;
    private XSSFSheet sheet;
    private final SparklineHandler sparklines = new SparklineHandler();

    @BeforeEach
    void setUp() {
        workbook = new XSSFWorkbook();
        sheet = workbook.createSheet("Data");
        sheet.createRow(0).createCell(0).setCellValue("Region");
        for (int r = 1; r <= 3; r++) {
            XSSFRow row = sheet.createRow(r);
            row.createCell(0).setCellValue("Row " + r);
            for (int c = 1; c <= 4; c++) {
                row.createCell(c).setCellValue(r * c * 1.0);
            }
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

    private String sheetXml(XSSFSheet target) {
        return target.getCTWorksheet().toString();
    }

    @Test
    @DisplayName("one sparkline per target cell, each pointed at its own row of numbers")
    void drawsOnePerRow() throws Exception {
        String detail = sparklines.execute(workbook, props("range", "F2:F4", "dataRange", "B2:E4"));

        String xml = sheetXml(sheet);
        assertThat(xml)
                .contains(SparklineHandler.SPARKLINE_EXT_URI)
                .contains("Data!B2:E2").contains("<xm:sqref>F2</xm:sqref>")
                .contains("Data!B4:E4").contains("<xm:sqref>F4</xm:sqref>");
        assertThat(detail).isNull();
    }

    @Test
    @DisplayName("across a row, each sparkline takes a column of numbers instead")
    void drawsOnePerColumn() throws Exception {
        sparklines.execute(workbook, props("range", "B6:E6", "dataRange", "B2:E4"));

        String xml = sheetXml(sheet);
        assertThat(xml)
                .contains("Data!B2:B4").contains("<xm:sqref>B6</xm:sqref>")
                .contains("Data!E2:E4").contains("<xm:sqref>E6</xm:sqref>");
    }

    @Test
    @DisplayName("the group carries the type and colour that were asked for")
    void honoursTypeAndColour() throws Exception {
        sparklines.execute(workbook, props(
                "range", "F2:F4", "dataRange", "B2:E4", "type", "column", "color", "#15803D"));

        String xml = sheetXml(sheet);
        assertThat(xml)
                .contains("type=\"column\"")
                .contains("<x14:colorSeries rgb=\"FF15803D\"/>");
    }

    @Test
    @DisplayName("a line group carries no type attribute, which is how Excel writes the default")
    void leavesLineImplicit() throws Exception {
        sparklines.execute(workbook, props("range", "F2:F4", "dataRange", "B2:E4"));

        assertThat(sheetXml(sheet)).contains("<x14:sparklineGroup displayEmptyCellsAs=\"gap\">");
    }

    @Test
    @DisplayName("a second call joins the extension already there rather than opening another")
    void reusesTheExtension() throws Exception {
        sparklines.execute(workbook, props("range", "F2:F4", "dataRange", "B2:E4"));

        sparklines.execute(workbook, props("range", "G2:G4", "dataRange", "B2:E4", "type", "column"));

        String xml = sheetXml(sheet);
        assertThat(xml.split(java.util.regex.Pattern.quote(SparklineHandler.SPARKLINE_EXT_URI), -1))
                .as("two extensions with the same uri is what makes Excel offer to repair a file")
                .hasSize(2);
        assertThat(xml.split("<x14:sparklineGroup ", -1))
                .as("one group per call, both inside the single sparklineGroups element")
                .hasSize(3);
        assertThat(xml.split("<x14:sparklineGroups", -1)).hasSize(2);
    }

    @Test
    @DisplayName("the extension is still in the file after a save and reopen")
    void survivesTheSave() throws Exception {
        sparklines.execute(workbook, props("range", "F2:F4", "dataRange", "B2:E4"));

        File file = tempDir.resolve("sparklines.xlsx").toFile();
        try (FileOutputStream out = new FileOutputStream(file)) {
            workbook.write(out);
        }
        try (XSSFWorkbook reopened = new XSSFWorkbook(file)) {
            String xml = sheetXml(reopened.getSheet("Data"));
            assertThat(xml)
                    .contains(SparklineHandler.SPARKLINE_EXT_URI)
                    .contains("<xm:sqref>F2</xm:sqref>");
        }
    }

    @Test
    @DisplayName("the extension uri keeps the exact casing Excel matches on")
    void keepsTheUriCasing() {
        assertThat(SparklineHandler.SPARKLINE_EXT_URI)
                .as("Excel compares this GUID case-sensitively: 4FD2 is silently ignored, 4fd2 works")
                .isEqualTo("{05C60535-1F16-4fd2-B633-F4F36F0B64E0}");
    }

    @Test
    @DisplayName("a data range whose rows do not match the cells is refused with both counts")
    void refusesAMismatchedDataRange() {
        var properties = props( "range", "F2:F4", "dataRange", "B2:E3");
        assertThatThrownBy(() -> sparklines.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2 rows for 3 cells");
    }

    @Test
    @DisplayName("a block of target cells is refused — sparklines go down a column or across a row")
    void refusesABlockOfTargets() {
        var properties = props( "range", "F2:G4", "dataRange", "B2:E4");
        assertThatThrownBy(() -> sparklines.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one column or one row");
    }

    @Test
    @DisplayName("an unknown type names the three that work")
    void refusesAnUnknownType() {
        var properties = props( "range", "F2:F4", "dataRange", "B2:E4", "type", "pie");
        assertThatThrownBy(() -> sparklines.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("winLoss");
    }

    @Test
    @DisplayName("a sheet name needing quotes is quoted inside the formula element")
    void quotesASheetName() throws Exception {
        workbook.setSheetName(0, "Sales 2026");

        sparklines.execute(workbook, props("range", "F2:F4", "dataRange", "'Sales 2026'!B2:E4"));

        assertThat(sheetXml(workbook.getSheetAt(0))).contains("<xm:f>'Sales 2026'!B2:E2</xm:f>");
    }
}
