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
    private final List<String> columns;
    private final List<String> existingFormulas;
}
