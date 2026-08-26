package com.ap0stole.sheetsmith.services.excel;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.io.IOException;

public class CreateTestData {

    public static void main(String[] args) throws IOException {
        // Створюємо новий ексель-файл
        Workbook workbook = new XSSFWorkbook();

        // Додаємо тестові дані до файлу
        createTestDataSet(workbook);

        // Збереження ексель-файлу
        try (FileOutputStream fileOut = new FileOutputStream("C:\\Users\\Game ON\\IdeaProjects\\reddis-test\\sheetsmith-java\\src\\test\\resources\\test_data.xlsx")) {
            workbook.write(fileOut);
        }

        // Закриття ресурсів
        workbook.close();
    }

    public static void createTestDataSet(Workbook workbook) throws IOException {
        // Створюємо лист з назвою "Data"
        Sheet sheet = workbook.createSheet("Data");

        // Додаємо заголовки
        Row headerRow = sheet.createRow(0);
        headerRow.createCell(0).setCellValue("Month");
        headerRow.createCell(1).setCellValue("Sales");

        // Додаємо тестові дані
        String[] months = {"January", "February", "March", "April", "May", "June", "July", "August", "September", "October"};
        int[] sales = {200, 300, 400, 500, 600, 700, 800, 900, 1000, 1100};

        for (int i = 0; i < months.length; i++) {
            Row dataRow = sheet.createRow(i + 1);
            dataRow.createCell(0).setCellValue(months[i]);
            dataRow.createCell(1).setCellValue(sales[i]);
        }
    }
}
