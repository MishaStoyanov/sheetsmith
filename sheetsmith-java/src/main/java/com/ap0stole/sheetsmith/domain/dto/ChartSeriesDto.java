package com.ap0stole.sheetsmith.domain.dto;

/**
 * One series of an existing chart. The two ranges are A1 formulas as the file stores them
 * ({@code Sheet1!$A$2:$A$6}), so the preview resolves them against the sheet it already parsed
 * rather than shipping the values twice.
 *
 * @param name           the series label, or null when the chart does not carry one
 * @param categoriesRef  the category (x) range, or null when the series has none
 * @param valuesRef      the values (y) range, or null when POI could not read it
 */
public record ChartSeriesDto(String name, String categoriesRef, String valuesRef) {
}
