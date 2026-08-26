package com.ap0stole.sheetsmith.excel_improver;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public interface ExcelAction {
    void execute(XSSFWorkbook workbook);
}
