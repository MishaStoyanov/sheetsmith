package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.configs.ChatConfig;
import lombok.RequiredArgsConstructor;
import com.ap0stole.sheetsmith.domain.dto.ChartDefinitionDto;
import com.ap0stole.sheetsmith.domain.dto.ChartSeriesDto;
import com.ap0stole.sheetsmith.domain.dto.ExcelSchemaDto;
import com.ap0stole.sheetsmith.domain.dto.SheetSchemaDto;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xddf.usermodel.chart.XDDFBar3DChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFBarChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFCategoryAxis;
import org.apache.poi.xddf.usermodel.chart.XDDFChartAxis;
import org.apache.poi.xddf.usermodel.chart.XDDFChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFDoughnutChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFLine3DChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFLineChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFPie3DChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFPieChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFValueAxis;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTSerTx;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTStrRef;
import org.apache.poi.ss.usermodel.DateUtil;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SchemaExtractorService {

    /** Rows read before deciding a column's type; the answer stops changing long before this. */
    private static final int COLUMN_TYPE_SAMPLE = 50;


    /** What every "I cannot tell" answer in this file says, so they all say the same thing. */
    private static final String UNKNOWN = "unknown";

    private final ChatConfig chatConfig;

    public ExcelSchemaDto extract(String filePath) {
        try (FileInputStream fis = new FileInputStream(filePath);
             XSSFWorkbook workbook = new XSSFWorkbook(fis)) {

            List<SheetSchemaDto> sheets = new ArrayList<>();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                SheetSchemaDto schema = extractSheet(workbook.getSheetAt(i));
                if (schema != null) sheets.add(schema);
            }

            if (sheets.isEmpty()) {
                throw new ApiException(ErrorCode.PROCESSING_ERROR, "Workbook has no sheets with data");
            }

            log.debug("Extracted schema from '{}': {} sheet(s)", filePath, sheets.size());
            return ExcelSchemaDto.builder().sheets(sheets).charts(extractCharts(workbook)).build();

        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to extract schema from {}", filePath, ex);
            throw new ApiException(ErrorCode.FILE_INVALID, "Cannot read Excel file: " + ex.getMessage());
        }
    }

    private SheetSchemaDto extractSheet(XSSFSheet sheet) {
        int firstRowIdx = sheet.getFirstRowNum();
        XSSFRow headerRow = null;

        while (firstRowIdx <= sheet.getLastRowNum()) {
            headerRow = sheet.getRow(firstRowIdx);
            if (headerRow != null && headerRow.getPhysicalNumberOfCells() > 0) break;
            firstRowIdx++;
        }

        if (headerRow == null) {
            log.debug("Sheet '{}' is empty, skipping", sheet.getSheetName());
            return null;
        }

        int firstColIdx = headerRow.getFirstCellNum();
        int lastColIdx = headerRow.getLastCellNum() - 1;
        int lastRowIdx = sheet.getLastRowNum();

        String headerRange = new CellReference(firstRowIdx, firstColIdx).formatAsString() + ":" +
                new CellReference(firstRowIdx, lastColIdx).formatAsString();
        String dataRange = new CellReference(firstRowIdx + 1, firstColIdx).formatAsString() + ":" +
                new CellReference(lastRowIdx, lastColIdx).formatAsString();

        // A gap in the header row is common in real sheets. The placeholder keeps the list
        // positional, because the LLM derives columnIndex from a column's place in it.
        List<SheetSchemaDto.ColumnSchema> columns = new ArrayList<>();
        for (int i = firstColIdx; i <= lastColIdx; i++) {
            Cell header = headerRow.getCell(i);
            String name = header == null ? "" : header.toString().trim();
            String label = name.isEmpty() ? "(unnamed column " + CellReference.convertNumToColString(i) + ")" : name;
            columns.add(new SheetSchemaDto.ColumnSchema(label, columnType(sheet, i, firstRowIdx + 1, lastRowIdx)));
        }

        return SheetSchemaDto.builder()
                .sheetName(sheet.getSheetName())
                .headerRange(headerRange)
                .dataRange(dataRange)
                .columns(columns)
                .existingFormulas(extractFormulas(sheet))
                .build();
    }

    /**
     * How a column's values are stored, read from the cells rather than guessed from the header.
     * <p>
     * This exists because a header lies about storage. "amount" reads as a number and is very often
     * text — an export writes strings, and a number format laid over strings shows nothing at all.
     * The planner cannot see the cells, so without this it proposes that no-op and the run reports
     * a step that succeeded and changed nothing.
     * <p>
     * A sample rather than the whole column: the answer is the same after a few dozen rows and a
     * schema is not the place to walk a hundred thousand of them. Blank cells do not vote, so a
     * column with gaps still answers by what it holds; a column of nothing but gaps is empty, and
     * one holding genuinely different kinds is mixed rather than whichever came first.
     */
    private String columnType(XSSFSheet sheet, int columnIdx, int firstDataRow, int lastRow) {
        String seen = null;
        int sampled = 0;
        for (int r = firstDataRow; r <= lastRow && sampled < COLUMN_TYPE_SAMPLE; r++) {
            XSSFRow row = sheet.getRow(r);
            Cell cell = row == null ? null : row.getCell(columnIdx);
            String kind = typeOf(cell);
            if (kind == null) {
                continue;
            }
            sampled++;
            if (seen == null) {
                seen = kind;
            } else if (!seen.equals(kind)) {
                return "mixed";
            }
        }
        return seen == null ? "empty" : seen;
    }

    /** Null for a cell that holds nothing, which is a gap rather than a kind. */
    private String typeOf(Cell cell) {
        if (cell == null) {
            return null;
        }
        return switch (cell.getCellType()) {
            case FORMULA -> "formula";
            case BOOLEAN -> "boolean";
            case NUMERIC -> DateUtil.isCellDateFormatted(cell) ? "date" : "number";
            case STRING -> cell.getStringCellValue().trim().isEmpty() ? null : "text";
            default -> null;
        };
    }

    /**
     * Lists every formula cell in the sheet so follow-up prompts (round 2+) know what a
     * previous edit already added, and can CLEAR_CELLS it instead of leaving a duplicate.
     */
    private List<String> extractFormulas(XSSFSheet sheet) {
        List<String> formulas = new ArrayList<>();
        for (Row row : sheet) {
            for (Cell cell : row) {
                if (cell.getCellType() != CellType.FORMULA) continue;

                String ref = new CellReference(cell.getRowIndex(), cell.getColumnIndex()).formatAsString();
                String label = adjacentLabel(row, cell.getColumnIndex());
                String entry = ref + " = " + cell.getCellFormula();
                if (label != null) entry += " (label: \"" + label + "\")";
                formulas.add(entry);
            }
        }
        return formulas;
    }

    /**
     * The text beside a formula, so a follow-up prompt can tell "Total" from "Average" and clear the
     * right one instead of adding a duplicate.
     * <p>
     * This is the one thing the planner sends that is read out of a cell rather than out of the
     * sheet's structure, so an instance running with the chat off does not send it: there, the
     * promise is that nothing but names, headers, ranges and formula text leaves the machine, and a
     * label is none of those. The cost is a follow-up round that has to tell two totals apart by
     * their formulas alone.
     */
    private String adjacentLabel(Row row, int columnIndex) {
        if (!chatConfig.isEnabled()) return null;
        if (columnIndex <= 0) return null;
        Cell left = row.getCell(columnIndex - 1);
        if (left == null || left.getCellType() != CellType.STRING) return null;
        String value = left.getStringCellValue();
        return (value == null || value.isBlank()) ? null : value;
    }

    /**
     * Reads every chart in the workbook (charts often live on their own sheet with no header
     * row, e.g. "AI_Chart", so this scans all sheets independently of the header-based loop
     * above). It feeds two consumers at once: RENAME_CHART_TITLE/RENAME_CHART_AXIS need the prose
     * line to target a chart, and the preview needs the series ranges to draw the real thing.
     * <p>
     * Charts come from files we did not write, so every step is guarded: a chart POI chokes on is
     * dropped, never allowed to take the rest of the workbook's schema down with it.
     */
    private List<ChartDefinitionDto> extractCharts(XSSFWorkbook workbook) {
        List<ChartDefinitionDto> result = new ArrayList<>();
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            XSSFSheet sheet = workbook.getSheetAt(i);
            List<XSSFChart> charts;
            try {
                XSSFDrawing drawing = sheet.getDrawingPatriarch();
                charts = drawing == null ? List.of() : drawing.getCharts();
            } catch (Exception ex) {
                log.warn("Cannot read the drawing on sheet '{}': {}", sheet.getSheetName(), ex.getMessage());
                continue;
            }

            for (int c = 0; c < charts.size(); c++) {
                try {
                    result.add(describeChart(charts.get(c), sheet.getSheetName(), i, c));
                } catch (Exception ex) {
                    log.warn("Skipping unreadable chart {} on sheet '{}': {}", c, sheet.getSheetName(), ex.getMessage());
                }
            }
        }
        return result;
    }

    private ChartDefinitionDto describeChart(XSSFChart chart, String sheetName, int sheetIndex, int chartIndex) {
        String title = chart.getTitleText() != null ? chart.getTitleText().toString() : "(untitled)";

        List<String> axes = new ArrayList<>();
        for (XDDFChartAxis axis : chart.getAxes()) {
            if (axis instanceof XDDFCategoryAxis) axes.add("category axis");
            else if (axis instanceof XDDFValueAxis) axes.add("value axis");
        }

        String type = UNKNOWN;
        List<ChartSeriesDto> series = new ArrayList<>();
        for (XDDFChartData data : chart.getChartSeries()) {
            String kind = chartType(data);
            if (!UNKNOWN.equals(kind) && UNKNOWN.equals(type)) type = kind;
            for (int s = 0; s < data.getSeriesCount(); s++) {
                try {
                    series.add(describeSeries(data.getSeries(s)));
                } catch (Exception ex) {
                    log.warn("Skipping unreadable series {} of chart '{}': {}", s, title, ex.getMessage());
                }
            }
        }

        return new ChartDefinitionDto(sheetName, sheetIndex, chartIndex, type, title, List.copyOf(axes), List.copyOf(series));
    }

    private ChartSeriesDto describeSeries(XDDFChartData.Series series) {
        XDDFDataSource<?> categories = series.getCategoryData();
        XDDFDataSource<? extends Number> values = series.getValuesData();
        return new ChartSeriesDto(
                seriesName(series),
                categories == null ? null : categories.getFormula(),
                values == null ? null : values.getFormula());
    }

    /**
     * POI exposes no getter for a series title, only the type-specific CT object — hence the
     * switch. An unknown chart kind simply goes unnamed rather than blocking the rest.
     */
    private String seriesName(XDDFChartData.Series series) {
        CTSerTx text = switch (series) {
            case XDDFBarChartData.Series s -> s.getCTBarSer().getTx();
            case XDDFPieChartData.Series s -> s.getCTPieSer().getTx();
            case XDDFLineChartData.Series s -> s.getCTLineSer().getTx();
            default -> null;
        };
        if (text == null) return null;
        if (text.isSetV()) return blankToNull(text.getV());

        CTStrRef ref = text.getStrRef();
        if (ref == null) return null;
        if (ref.getStrCache() != null && ref.getStrCache().sizeOfPtArray() > 0) {
            return blankToNull(ref.getStrCache().getPtArray(0).getV());
        }
        return blankToNull(ref.getF());
    }

    private String chartType(XDDFChartData data) {
        return switch (data) {
            case XDDFBarChartData _ -> "bar";
            case XDDFBar3DChartData _ -> "bar";
            case XDDFPieChartData _ -> "pie";
            case XDDFPie3DChartData _ -> "pie";
            case XDDFDoughnutChartData _ -> "pie";
            case XDDFLineChartData _ -> "line";
            case XDDFLine3DChartData _ -> "line";
            default -> UNKNOWN;
        };
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
