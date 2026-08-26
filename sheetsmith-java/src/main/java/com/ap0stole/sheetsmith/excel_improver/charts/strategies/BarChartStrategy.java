package com.ap0stole.sheetsmith.excel_improver.charts.strategies;

import com.ap0stole.sheetsmith.excel_improver.charts.ChartStrategy;
import org.apache.poi.xddf.usermodel.XDDFColor;
import org.apache.poi.xddf.usermodel.XDDFSolidFillProperties;
import org.apache.poi.xddf.usermodel.chart.*;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTBarSer;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTDPt;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTDLbls;
import org.openxmlformats.schemas.drawingml.x2006.main.CTShapeProperties;

import java.awt.Color;

public class BarChartStrategy implements ChartStrategy {

    private static final byte[][] TOP_20_COLORS = {
            {(byte)255, (byte)220, (byte)30},  {(byte)245, (byte)150, (byte)45}, {(byte)180, (byte)110, (byte)170}, {(byte)200, (byte)65, (byte)65},  {(byte)90, (byte)140, (byte)185},
            {(byte)46, (byte)204, (byte)113},  {(byte)52, (byte)152, (byte)219},  {(byte)155, (byte)89, (byte)182},  {(byte)52, (byte)73, (byte)94},   {(byte)22, (byte)160, (byte)133},
            {(byte)39, (byte)174, (byte)96},   {(byte)41, (byte)128, (byte)185},  {(byte)142, (byte)68, (byte)173},  {(byte)44, (byte)62, (byte)80},   {(byte)241, (byte)196, (byte)15},
            {(byte)230, (byte)126, (byte)34},  {(byte)231, (byte)76, (byte)60},   {(byte)149, (byte)165, (byte)166}, {(byte)127, (byte)140, (byte)141}, {(byte)211, (byte)84, (byte)0}
    };

    @Override
    public void draw(XSSFChart chart, XDDFDataSource<String> categories, XDDFNumericalDataSource<Double> values, String title) {
        XDDFCategoryAxis bottomAxis = chart.createCategoryAxis(AxisPosition.BOTTOM);
        XDDFValueAxis leftAxis = chart.createValueAxis(AxisPosition.LEFT);

        bottomAxis.setCrosses(AxisCrosses.AUTO_ZERO);
        leftAxis.setCrossBetween(AxisCrossBetween.BETWEEN);

        // Ужимаем шкалу, чтобы не было пустых 120 при максимуме 90
        leftAxis.setMinimum(0.0);

        leftAxis.setMajorTickMark(AxisTickMark.OUT);
        bottomAxis.setMajorTickMark(AxisTickMark.NONE);

        XDDFBarChartData data = (XDDFBarChartData) chart.createData(ChartTypes.BAR, bottomAxis, leftAxis);
        data.setBarDirection(BarDirection.COL);

        // GapWidth 60 делает бары широкими и профессиональными
        data.setGapWidth(120);
        data.setVaryColors(false);

        XDDFChartData.Series series = data.addSeries(categories, values);
        series.setTitle(title, null);

        addSeriesLabels(series);

        if (chart.getOrAddLegend() != null) {
            chart.deleteLegend();
        }

        cleanChartStyle(chart, bottomAxis, leftAxis);
        applyHybridPalette(series, values.getPointCount());

        chart.plot(data);
    }

    private void addSeriesLabels(XDDFChartData.Series series) {
        if (series instanceof XDDFBarChartData.Series) {
            CTBarSer ctSer = ((XDDFBarChartData.Series) series).getCTBarSer();
            CTDLbls dLbls = ctSer.isSetDLbls() ? ctSer.getDLbls() : ctSer.addNewDLbls();

            // УБИРАЕМ КВАДРАТИКИ (Legend Key) для чистоты
            dLbls.addNewShowLegendKey().setVal(false);

            dLbls.addNewShowVal().setVal(true);
            dLbls.addNewShowCatName().setVal(false);
            dLbls.addNewShowSerName().setVal(false);
            dLbls.addNewShowLeaderLines().setVal(false);
        }
    }

    private void cleanChartStyle(XSSFChart chart, XDDFCategoryAxis bottom, XDDFValueAxis left) {
        if (!chart.getCTChartSpace().isSetSpPr()) chart.getCTChartSpace().addNewSpPr();
        chart.getCTChartSpace().getSpPr().addNewLn().addNewNoFill();

        XDDFSolidFillProperties axisColor = new XDDFSolidFillProperties(XDDFColor.from(new byte[]{(byte)170, (byte)170, (byte)170}));

        bottom.getOrAddShapeProperties().setLineProperties(new org.apache.poi.xddf.usermodel.XDDFLineProperties());
        bottom.getOrAddShapeProperties().getLineProperties().setFillProperties(axisColor);

        left.getOrAddShapeProperties().setLineProperties(new org.apache.poi.xddf.usermodel.XDDFLineProperties());
        left.getOrAddShapeProperties().getLineProperties().setFillProperties(axisColor);
    }

    private void applyHybridPalette(XDDFChartData.Series series, int pointCount) {
        if (!(series instanceof XDDFBarChartData.Series)) return;
        CTBarSer ctBarSer = ((XDDFBarChartData.Series) series).getCTBarSer();

        for (int i = 0; i < pointCount; i++) {
            byte[] rgb = (i < TOP_20_COLORS.length) ? TOP_20_COLORS[i] : generateColor(i, pointCount);

            CTDPt dPt = ctBarSer.addNewDPt();
            dPt.addNewIdx().setVal((long) i);

            CTShapeProperties spPr = dPt.addNewSpPr();
            spPr.addNewSolidFill().addNewSrgbClr().setVal(rgb);
            spPr.addNewLn().addNewNoFill();
        }
    }

    private byte[] generateColor(int i, int pointCount) {
        float hue = (float) i / pointCount;
        Color generated = Color.getHSBColor(hue, 0.6f, 0.85f);
        return new byte[]{(byte)generated.getRed(), (byte)generated.getGreen(), (byte)generated.getBlue()};
    }

    @Override
    public ChartTypes getChartType() { return ChartTypes.BAR; }
}