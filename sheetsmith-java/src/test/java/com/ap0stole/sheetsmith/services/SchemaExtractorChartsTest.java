package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.configs.ChatConfig;
import com.ap0stole.sheetsmith.domain.dto.ChartDefinitionDto;
import com.ap0stole.sheetsmith.domain.dto.ChartSeriesDto;
import com.ap0stole.sheetsmith.domain.dto.ExcelSchemaDto;
import com.ap0stole.sheetsmith.domain.dto.SheetSchemaDto;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xddf.usermodel.chart.AxisPosition;
import org.apache.poi.xddf.usermodel.chart.ChartTypes;
import org.apache.poi.xddf.usermodel.chart.XDDFCategoryAxis;
import org.apache.poi.xddf.usermodel.chart.XDDFChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory;
import org.apache.poi.xddf.usermodel.chart.XDDFNumericalDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFValueAxis;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The preview draws whatever comes out of here, so a wrong chart definition is worse than none —
 * it looks authoritative. These cover the shapes a chart arrives in, including the ones POI can
 * only half-read.
 */
class SchemaExtractorChartsTest {

    private SchemaExtractorService service;
    private Path tempDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        this.service = new SchemaExtractorService(new ChatConfig());
        this.tempDir = tempDir;
    }

    @Test
    @DisplayName("a bar chart comes back as a bar chart, with its title and both ranges")
    void readsABarChart() throws Exception {
        Path file = write(workbook -> {
            XSSFSheet sales = salesSheet(workbook, "Sales");
            barChart(sales, sales, "Revenue by product", "Revenue");
        });

        ChartDefinitionDto chart = onlyChart(service.extract(file.toString()));

        assertThat(chart.type()).isEqualTo("bar");
        assertThat(chart.title()).isEqualTo("Revenue by product");
        assertThat(chart.sheetName()).isEqualTo("Sales");
        assertThat(chart.sheetIndex()).isZero();
        assertThat(chart.chartIndex()).isZero();
        assertThat(chart.axes()).containsExactlyInAnyOrder("category axis", "value axis");

        ChartSeriesDto series = chart.series().getFirst();
        assertThat(series.name()).isEqualTo("Revenue");
        assertThat(series.categoriesRef()).isEqualTo("Sales!$A$2:$A$4");
        assertThat(series.valuesRef()).isEqualTo("Sales!$B$2:$B$4");
    }

    @Test
    @DisplayName("a pie chart is not silently turned into a bar chart")
    void readsAPieChart() throws Exception {
        Path file = write(workbook -> {
            XSSFSheet sales = salesSheet(workbook, "Sales");
            pieChart(sales, sales, "Share of revenue", "Revenue");
        });

        ChartDefinitionDto chart = onlyChart(service.extract(file.toString()));

        assertThat(chart.type()).isEqualTo("pie");
        assertThat(chart.title()).isEqualTo("Share of revenue");
        // A pie has no axes at all — the prompt line has to keep saying so.
        assertThat(chart.axes()).isEmpty();
        assertThat(chart.toPromptLine()).doesNotContain("axis");
        assertThat(chart.series().getFirst().valuesRef()).isEqualTo("Sales!$B$2:$B$4");
    }

    @Test
    @DisplayName("a chart on a sheet of its own still points back at the data sheet")
    void readsAChartOnItsOwnSheet() throws Exception {
        Path file = write(workbook -> {
            XSSFSheet sales = salesSheet(workbook, "Sales");
            XSSFSheet target = workbook.createSheet("AI_Chart");
            barChart(target, sales, "Revenue", "Revenue");
        });

        ChartDefinitionDto chart = onlyChart(service.extract(file.toString()));

        assertThat(chart.sheetName()).isEqualTo("AI_Chart");
        assertThat(chart.sheetIndex()).isEqualTo(1);
        assertThat(chart.series().getFirst().categoriesRef()).startsWith("Sales!");
        assertThat(chart.series().getFirst().valuesRef()).startsWith("Sales!");
    }

    @Test
    @DisplayName("a sheet name with a space is quoted, because the preview has to parse it back")
    void quotesASheetNameWithASpace() throws Exception {
        Path file = write(workbook -> {
            XSSFSheet sales = salesSheet(workbook, "Q3 Sales");
            barChart(sales, sales, "Revenue", "Revenue");
        });

        ChartSeriesDto series = onlyChart(service.extract(file.toString())).series().getFirst();

        assertThat(series.valuesRef()).isEqualTo("'Q3 Sales'!$B$2:$B$4");
    }

    @Test
    @DisplayName("a workbook with no charts yields no definitions, not a null")
    void handlesAWorkbookWithoutCharts() throws Exception {
        Path file = write(workbook -> salesSheet(workbook, "Sales"));

        assertThat(service.extract(file.toString()).getCharts()).isEmpty();
    }

    @Test
    @DisplayName("only the sheet that has a chart contributes one")
    void skipsSheetsWithoutCharts() throws Exception {
        Path file = write(workbook -> {
            salesSheet(workbook, "Plain");
            XSSFSheet charted = salesSheet(workbook, "Charted");
            barChart(charted, charted, "Revenue", "Revenue");
        });

        List<ChartDefinitionDto> charts = service.extract(file.toString()).getCharts();

        assertThat(charts).hasSize(1);
        assertThat(charts.getFirst().sheetName()).isEqualTo("Charted");
        assertThat(charts.getFirst().sheetIndex()).isEqualTo(1);
    }

    @Test
    @DisplayName("a chart with nothing plotted in it is reported, not thrown over")
    void survivesAnEmptyChart() throws Exception {
        Path file = write(workbook -> {
            XSSFSheet sales = salesSheet(workbook, "Sales");
            XSSFDrawing drawing = sales.createDrawingPatriarch();
            XSSFChart chart = drawing.createChart(drawing.createAnchor(0, 0, 0, 0, 4, 1, 12, 15));
            chart.setTitleText("Nothing here");
            // deliberately never plotted — the plot area stays empty
        });

        ExcelSchemaDto schema = assertNoThrow(file);

        ChartDefinitionDto chart = onlyChart(schema);
        assertThat(chart.type()).isEqualTo("unknown");
        assertThat(chart.series()).isEmpty();
        // The rest of the workbook must survive it.
        assertThat(schema.getSheets()).hasSize(1);
        assertThat(schema.getSheets().getFirst().getColumns().stream()
                .map(SheetSchemaDto.ColumnSchema::name).toList()).containsExactly("Product", "Revenue");
    }

    @Test
    @DisplayName("a chart kind we do not draw is labelled unknown rather than mislabelled")
    void doesNotGuessAtAnUnsupportedChartKind() throws Exception {
        Path file = write(workbook -> {
            XSSFSheet sales = salesSheet(workbook, "Sales");
            XSSFDrawing drawing = sales.createDrawingPatriarch();
            XSSFChart chart = drawing.createChart(drawing.createAnchor(0, 0, 0, 0, 4, 1, 12, 15));
            chart.setTitleText("Scattered");
            XDDFValueAxis bottom = chart.createValueAxis(AxisPosition.BOTTOM);
            XDDFValueAxis left = chart.createValueAxis(AxisPosition.LEFT);
            XDDFChartData data = chart.createData(ChartTypes.SCATTER, bottom, left);
            data.addSeries(numbers(sales, 0), numbers(sales, 1));
            chart.plot(data);
        });

        ChartDefinitionDto chart = onlyChart(assertNoThrow(file));

        assertThat(chart.type()).isEqualTo("unknown");
        assertThat(chart.title()).isEqualTo("Scattered");
        assertThat(chart.series()).hasSize(1);
        assertThat(chart.series().getFirst().name()).isNull();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ExcelSchemaDto assertNoThrow(Path file) {
        assertThatCode(() -> service.extract(file.toString())).doesNotThrowAnyException();
        return service.extract(file.toString());
    }

    private ChartDefinitionDto onlyChart(ExcelSchemaDto schema) {
        assertThat(schema.getCharts()).hasSize(1);
        return schema.getCharts().getFirst();
    }

    private XSSFSheet salesSheet(XSSFWorkbook workbook, String name) {
        XSSFSheet sheet = workbook.createSheet(name);
        XSSFRow header = sheet.createRow(0);
        header.createCell(0).setCellValue("Product");
        header.createCell(1).setCellValue("Revenue");
        String[] products = {"Widget A", "Widget B", "Widget C"};
        double[] revenue = {1240, 980, 1610};
        for (int i = 0; i < products.length; i++) {
            XSSFRow row = sheet.createRow(i + 1);
            row.createCell(0).setCellValue(products[i]);
            row.createCell(1).setCellValue(revenue[i]);
        }
        return sheet;
    }

    private void barChart(XSSFSheet target, XSSFSheet source, String title, String seriesName) {
        XSSFChart chart = newChart(target, title);
        XDDFCategoryAxis bottom = chart.createCategoryAxis(AxisPosition.BOTTOM);
        XDDFValueAxis left = chart.createValueAxis(AxisPosition.LEFT);
        XDDFChartData data = chart.createData(ChartTypes.BAR, bottom, left);
        data.addSeries(labels(source), numbers(source, 1)).setTitle(seriesName, null);
        chart.plot(data);
    }

    private void pieChart(XSSFSheet target, XSSFSheet source, String title, String seriesName) {
        XSSFChart chart = newChart(target, title);
        XDDFChartData data = chart.createData(ChartTypes.PIE, null, null);
        data.addSeries(labels(source), numbers(source, 1)).setTitle(seriesName, null);
        chart.plot(data);
    }

    private XSSFChart newChart(XSSFSheet target, String title) {
        XSSFDrawing drawing = target.createDrawingPatriarch();
        XSSFChart chart = drawing.createChart(drawing.createAnchor(0, 0, 0, 0, 4, 1, 12, 15));
        chart.setTitleText(title);
        chart.setTitleOverlay(false);
        return chart;
    }

    private XDDFDataSource<String> labels(XSSFSheet source) {
        return XDDFDataSourcesFactory.fromStringCellRange(source, new CellRangeAddress(1, 3, 0, 0));
    }

    private XDDFNumericalDataSource<Double> numbers(XSSFSheet source, int column) {
        return XDDFDataSourcesFactory.fromNumericCellRange(source, new CellRangeAddress(1, 3, column, column));
    }

    private Path write(Consumer<XSSFWorkbook> build) throws Exception {
        Path file = tempDir.resolve("charts.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             FileOutputStream out = new FileOutputStream(file.toFile())) {
            build.accept(workbook);
            workbook.write(out);
        }
        return file;
    }
}
