package com.ap0stole.sheetsmith.domain.dto.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Edits the user made by hand in the preview grid, committed as one revision rather than one per
 * cell. Coordinates are 0-based, matching what the grid already tracks.
 *
 * @param cells        the cells whose value changed
 * @param sheetRenames old name to new, for tabs renamed in the same sitting
 */
public record CellEditsRequest(
        @Valid List<CellEdit> cells,
        Map<String, String> sheetRenames
) {

    /**
     * One cell, as the grid tracks it.
     *
     * @param sheetIndex which sheet, 0-based
     * @param row        0-based, so the first data row under a header is 1
     * @param column     0-based, so column A is 0
     * @param value      what was typed. An empty string clears the cell; the value is stored as
     *                   text or number the same way an upload would be read
     */
    public record CellEdit(
            @NotNull @Min(0) Integer sheetIndex,
            @NotNull @Min(0) Integer row,
            @NotNull @Min(0) Integer column,
            String value
    ) {
    }

    public List<CellEdit> safeCells() {
        return cells == null ? List.of() : cells;
    }

    public Map<String, String> safeRenames() {
        return sheetRenames == null ? Map.of() : sheetRenames;
    }

    public boolean isEmpty() {
        return safeCells().isEmpty() && safeRenames().isEmpty();
    }
}
