package com.ap0stole.sheetsmith.domain.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class SheetSchemaDto {
    private final String sheetName;
    private final String headerRange;
    private final String dataRange;
    private final List<ColumnSchema> columns;

    /**
     * A column's name and how its values are stored, which are different questions.
     * <p>
     * The name is what somebody calls it; the type is what Excel actually holds. A column headed
     * "amount" whose cells are strings takes a number format and shows nothing — so the planner
     * has to be told which it is, or it will keep proposing a step that quietly does nothing.
     * <p>
     * This is metadata about the column, never a value from it: the type is derived and the cells
     * themselves stay where they are.
     *
     * @param name  the header cell's text, or a placeholder when the header is blank
     * @param type  {@code text}, {@code number}, {@code date}, {@code boolean}, {@code formula},
     *              {@code mixed} where the column holds more than one, or {@code empty}
     */
    public record ColumnSchema(String name, String type) {

        @Override
        public String toString() {
            return name + " (" + type + ")";
        }
    }
    private final List<String> existingFormulas;
}
