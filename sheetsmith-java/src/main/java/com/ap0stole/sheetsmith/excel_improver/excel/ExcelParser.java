package com.ap0stole.sheetsmith.excel_improver.excel;

import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;

public class ExcelParser {
    public String getTableSchema(String filePath) {
        try (FileInputStream fis = new FileInputStream(filePath);
             XSSFWorkbook workbook = new XSSFWorkbook(fis)) {

            XSSFSheet sheet = workbook.getSheetAt(0);
            XSSFRow header = sheet.getRow(0);

            StringBuilder schema = new StringBuilder("Sheet: " + sheet.getSheetName() + ". Columns: ");
            header.forEach(cell -> schema.append(cell.getStringCellValue()).append(", "));

            int lastRow = sheet.getLastRowNum() + 1;
            schema.append("Total rows: ").append(lastRow);

            return schema.toString();
        } catch (Exception e) {
            return "Error reading schema";
        }
    }
}
