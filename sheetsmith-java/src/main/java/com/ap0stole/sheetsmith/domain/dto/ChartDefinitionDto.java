package com.ap0stole.sheetsmith.domain.dto;

import java.util.List;

/**
 * A chart as it actually exists in the workbook — including one that arrived inside the upload.
 * The preview draws from this, so guessing is not allowed: everything here is read back off the
 * file, and a chart POI cannot make sense of is left out rather than approximated.
 *
 * @param axes  the axis kinds POI could identify; empty for a pie, which has none
 */
public record ChartDefinitionDto(
        String sheetName,
        int sheetIndex,
        int chartIndex,
        String type,
        String title,
        List<String> axes,
        List<ChartSeriesDto> series
) {

    /** The prose line the planner prompt has always shown for an existing chart. */
    public String toPromptLine() {
        return "sheet \"" + sheetName + "\" (sheetIndex " + sheetIndex + "), chartIndex " + chartIndex
                + ": title \"" + title + "\"" + (axes.isEmpty() ? "" : ", has " + String.join(" and ", axes));
    }
}
