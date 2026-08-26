package com.ap0stole.sheetsmith.excel_improver.charts;
import com.ap0stole.sheetsmith.excel_improver.ExcelAction;
import lombok.AllArgsConstructor;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xddf.usermodel.chart.*;
import org.apache.poi.xssf.usermodel.*;

@AllArgsConstructor
public class CreateChartAction implements ExcelAction {
    private final String title;
    private final String sourceSheetName;
    private final String targetSheetName;
    private final String range;


    @Override
    public void execute(XSSFWorkbook workbook) {
        XSSFSheet sourceSheet = workbook.getSheet(sourceSheetName);
        XSSFSheet targetSheet = workbook.createSheet(targetSheetName);

        // Создаем "холст" для рисования
        XSSFDrawing drawing = targetSheet.createDrawingPatriarch();
        // Где будет располагаться график (Anchor)
        XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, 0, 0, 10, 20);

        XSSFChart chart = drawing.createChart(anchor);
        chart.setTitleText(title);
        chart.setTitleOverlay(false);

        // Настройка осей
        XDDFCategoryAxis bottomAxis = chart.createCategoryAxis(AxisPosition.BOTTOM);
        XDDFValueAxis leftAxis = chart.createValueAxis(AxisPosition.LEFT);

        // Данные (упрощенно для MVP: берем колонку A для категорий, B для значений)
        XDDFDataSource<String> categories = XDDFDataSourcesFactory.fromStringCellRange(sourceSheet, CellRangeAddress.valueOf("A2:A5"));
        XDDFNumericalDataSource<Double> values = XDDFDataSourcesFactory.fromNumericCellRange(sourceSheet, CellRangeAddress.valueOf("B2:B5"));

        XDDFChartData data = chart.createData(ChartTypes.BAR, bottomAxis, leftAxis);
        XDDFChartData.Series series = data.addSeries(categories, values);
        series.setTitle(title, null);

        chart.plot(data);
    }
}