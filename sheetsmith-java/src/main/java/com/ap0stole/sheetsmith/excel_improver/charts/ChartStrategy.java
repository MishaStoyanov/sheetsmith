package com.ap0stole.sheetsmith.excel_improver.charts;

import org.apache.poi.xddf.usermodel.chart.ChartTypes;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFNumericalDataSource;
import org.apache.poi.xssf.usermodel.XSSFChart;

public interface ChartStrategy {
    void draw(XSSFChart chart, XDDFDataSource<String> categories, XDDFNumericalDataSource<Double> values, String title);
    ChartTypes getChartType();
}
