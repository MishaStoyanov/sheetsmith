package com.ap0stole.sheetsmith.services.excel;

import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public class SheetResolver {

    private SheetResolver() {}

    /**
     * Resolves a sheet by name (priority), then by index, then falls back to sheet 0.
     * Throws if the specified name/index doesn't exist in the workbook.
     */
    public static XSSFSheet resolve(XSSFWorkbook workbook, String sheetName, Integer sheetIndex) {
        if (sheetName != null && !sheetName.isBlank()) {
            XSSFSheet sheet = workbook.getSheet(sheetName);
            if (sheet == null) {
                throw new IllegalArgumentException(
                        "Sheet not found: \"" + sheetName + "\". Available: " + availableSheets(workbook));
            }
            return sheet;
        }
        if (sheetIndex != null) {
            if (sheetIndex < 0 || sheetIndex >= workbook.getNumberOfSheets()) {
                throw new IllegalArgumentException(
                        "Sheet index " + sheetIndex + " out of bounds (workbook has "
                                + workbook.getNumberOfSheets() + " sheet(s))");
            }
            return workbook.getSheetAt(sheetIndex);
        }
        return workbook.getSheetAt(0);
    }

    private static String availableSheets(XSSFWorkbook workbook) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('"').append(workbook.getSheetName(i)).append('"');
        }
        return sb.append("]").toString();
    }
}
