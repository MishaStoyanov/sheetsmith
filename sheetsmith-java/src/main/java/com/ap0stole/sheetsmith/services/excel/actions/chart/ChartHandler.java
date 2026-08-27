package com.ap0stole.sheetsmith.services.excel.actions.chart;

import com.ap0stole.sheetsmith.services.excel.model.chart.ChartConfig;
import com.ap0stole.sheetsmith.services.excel.model.chart.RenameChartAxisConfig;
import com.ap0stole.sheetsmith.services.excel.model.chart.RenameChartTitleConfig;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xddf.usermodel.chart.*;
import org.apache.poi.xssf.usermodel.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class ChartHandler {

    private final Map<String, ChartStrategy> strategies = new HashMap<>();

    public ChartHandler() {
        strategies.put("barChart", new BarChartStrategy());
        strategies.put("pieChart", new PieChartStrategy());
    }

    public void execute(XSSFWorkbook workbook, ChartConfig config) {
        String rangeStr = config.getSourceRange();
        String sourceSheetName = config.getSourceSheet();
        Integer sourceSheetIndex = config.getSourceSheetIndex();

        if (rangeStr != null && rangeStr.contains("!")) {
            String[] parts = rangeStr.split("!");
            sourceSheetName = parts[0].replace("Sheet:", "").replace("'", "").trim();
            rangeStr = parts[1];
        }

        XSSFSheet sourceSheet = SheetResolver.resolve(workbook, sourceSheetName, sourceSheetIndex);

        String type = config.getChartType();
        ChartStrategy strategy = strategies.getOrDefault(type, strategies.get("barChart"));

        String targetName = (config.getTargetSheet() != null) ? config.getTargetSheet() : "AI_Chart";
        XSSFSheet targetSheet = workbook.getSheet(targetName);
        if (targetSheet == null) targetSheet = workbook.createSheet(targetName);

        CellRangeAddress rangeAddr = CellRangeAddress.valueOf(rangeStr);
        int rowCount = rangeAddr.getLastRow() - rangeAddr.getFirstRow() + 1;

        int width = (config.getChartWidth() != null)
                ? config.getChartWidth()
                : (rowCount <= 6 ? 6 : Math.min(14, Math.max(5, (int) (rowCount * 1.1) + 2)));
        int height = (config.getChartHeight() != null) ? config.getChartHeight() : 12;

        XSSFDrawing drawing = targetSheet.createDrawingPatriarch();
        XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, 2, 1, 2 + width, 1 + height);
        XSSFChart chart = drawing.createChart(anchor);

        String title = (config.getTitle() != null) ? config.getTitle() : "Data Visualization";
        chart.setTitleText(title);
        chart.setTitleOverlay(false);

        try {
            // Skip the header row if the value cell in the first row is non-numeric (i.e. a label)
            int firstDataRow = rangeAddr.getFirstRow();
            XSSFRow firstRow = sourceSheet.getRow(firstDataRow);
            if (firstRow != null) {
                var firstValueCell = firstRow.getCell(rangeAddr.getLastColumn());
                if (firstValueCell != null && firstValueCell.getCellType() == CellType.STRING) {
                    firstDataRow++;
                }
            }

            XDDFDataSource<String> categories = XDDFDataSourcesFactory.fromStringCellRange(sourceSheet,
                    new CellRangeAddress(firstDataRow, rangeAddr.getLastRow(),
                            rangeAddr.getFirstColumn(), rangeAddr.getFirstColumn()));

            XDDFNumericalDataSource<Double> values = XDDFDataSourcesFactory.fromNumericCellRange(sourceSheet,
                    new CellRangeAddress(firstDataRow, rangeAddr.getLastRow(),
                            rangeAddr.getLastColumn(), rangeAddr.getLastColumn()));

            strategy.draw(chart, categories, values, title);

        } catch (Exception e) {
            log.error("Failed to parse range or draw chart: {}", rangeStr, e);
        }
    }

    public void renameTitle(XSSFWorkbook workbook, RenameChartTitleConfig config) {
        XSSFChart chart = resolveChart(workbook, config.getSheetName(), config.getSheetIndex(), config.getChartIndex());
        chart.setTitleText(config.getNewTitle());
        chart.setTitleOverlay(false);
        log.info("Renamed chart title to '{}'", config.getNewTitle());
    }

    public void renameAxis(XSSFWorkbook workbook, RenameChartAxisConfig config) {
        XSSFChart chart = resolveChart(workbook, config.getSheetName(), config.getSheetIndex(), config.getChartIndex());
        String axisType = config.getAxis() == null ? "" : config.getAxis().trim().toLowerCase();

        XDDFChartAxis target = chart.getAxes().stream()
                .filter(a -> "category".equals(axisType) ? a instanceof XDDFCategoryAxis : a instanceof XDDFValueAxis)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Chart has no \"" + axisType + "\" axis to rename (pie charts have no axes)"));

        target.setTitle(config.getNewTitle());
        log.info("Renamed {} axis to '{}'", axisType, config.getNewTitle());
    }

    private XSSFChart resolveChart(XSSFWorkbook workbook, String sheetName, Integer sheetIndex, Integer chartIndex) {
        XSSFSheet sheet = SheetResolver.resolve(workbook, sheetName, sheetIndex);
        XSSFDrawing drawing = sheet.getDrawingPatriarch();
        if (drawing == null) {
            throw new IllegalArgumentException("Sheet \"" + sheet.getSheetName() + "\" has no charts");
        }
        List<XSSFChart> charts = drawing.getCharts();
        int idx = (chartIndex != null) ? chartIndex : 0;
        if (idx < 0 || idx >= charts.size()) {
            throw new IllegalArgumentException("Chart index " + idx + " out of bounds on sheet \""
                    + sheet.getSheetName() + "\" (" + charts.size() + " chart(s))");
        }
        return charts.get(idx);
    }
}
