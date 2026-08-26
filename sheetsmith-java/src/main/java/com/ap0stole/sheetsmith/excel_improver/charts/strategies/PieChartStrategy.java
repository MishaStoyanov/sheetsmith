package com.ap0stole.sheetsmith.excel_improver.charts.strategies;

import com.ap0stole.sheetsmith.excel_improver.charts.ChartStrategy;
import org.apache.poi.xddf.usermodel.chart.ChartTypes;
import org.apache.poi.xddf.usermodel.chart.XDDFChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFNumericalDataSource;
import org.apache.poi.xssf.usermodel.XSSFChart;

public class PieChartStrategy implements ChartStrategy {
    @Override
    public void draw(XSSFChart chart, XDDFDataSource<String> categories, XDDFNumericalDataSource<Double> values, String title) {
        // Pie Chart НЕ использует оси
        XDDFChartData data = chart.createData(ChartTypes.PIE, null, null);

        XDDFChartData.Series series = data.addSeries(categories, values);
        series.setTitle(title, null);
        chart.plot(data);
    }

    @Override
    public ChartTypes getChartType() { return ChartTypes.PIE; }
}