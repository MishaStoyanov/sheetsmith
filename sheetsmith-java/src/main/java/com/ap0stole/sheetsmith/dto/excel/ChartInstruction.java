package com.ap0stole.sheetsmith.dto.excel;

import com.ap0stole.sheetsmith.dto.excel.enums.ChartType;

public class ChartInstruction {
    public ChartType type;
    public String title;
    public String sourceSheetName;
    public String dataRange;
    public String targetSheetName = "Charts";
    public String barColor;
    public String fontFamily = "Calibri";
    public Integer fontSize = 12;
}