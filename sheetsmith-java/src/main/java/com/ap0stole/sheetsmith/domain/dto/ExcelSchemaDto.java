package com.ap0stole.sheetsmith.domain.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.stream.Collectors;

@Getter
@Builder
public class ExcelSchemaDto {

    private final List<SheetSchemaDto> sheets;
    private final List<ChartDefinitionDto> charts;

    private static final String NL = "\n";

    public String toPromptString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Workbook contains ").append(sheets.size()).append(" sheet(s):\n\n");
        for (int i = 0; i < sheets.size(); i++) {
            SheetSchemaDto s = sheets.get(i);
            sb.append("Sheet ").append(i).append(": \"").append(s.getSheetName()).append("\"\n");
            // Name and storage type together, because a header says what a column is called and
            // the type says what a step may do to it — "amount (text)" is the difference between
            // a number format that works and one that silently shows nothing.
            sb.append("  Columns: ").append(s.getColumns().stream()
                    .map(SheetSchemaDto.ColumnSchema::toString)
                    .collect(Collectors.joining(", "))).append(NL);
            sb.append("  Header range: ").append(s.getHeaderRange()).append("\n");
            sb.append("  Data range: ").append(s.getDataRange()).append("\n");
            if (s.getExistingFormulas() != null && !s.getExistingFormulas().isEmpty()) {
                sb.append("  Existing formulas (already in the sheet from earlier edits — clear before replacing):\n");
                for (String formula : s.getExistingFormulas()) {
                    sb.append("    - ").append(formula).append("\n");
                }
            }
            sb.append("\n");
        }
        if (charts != null && !charts.isEmpty()) {
            sb.append("Existing charts (already in the workbook from earlier edits):\n");
            for (ChartDefinitionDto chart : charts) {
                sb.append("  - ").append(chart.toPromptLine()).append("\n");
            }
        }
        return sb.toString().trim();
    }
}
