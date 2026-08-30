package com.ap0stole.sheetsmith.services.excel;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.util.Map;
import java.io.IOException;

public interface ActionHandler {
    String getType();

    /**
     * Applies the step to the workbook.
     *
     * @return a short detail worth showing next to {@link #describe}, or null when the sentence
     * {@code describe()} already wrote says everything. An action that can succeed over only part
     * of its input — a transform that skipped values it could not convert — must report that here,
     * or a partial result reaches the user as an unqualified success.
     */
    String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws IOException;

    /** The past-tense reading — what the chat's "how I got there" chain and the job history show. */
    default String describe(Map<String, Object> properties) {
        return describe(properties, StepTense.PAST);
    }

    /**
     * Plain-language summary of this step, imperative when it is being proposed in a plan card and
     * past when it has run. Users must never see raw enums, so override it.
     */
    default String describe(Map<String, Object> properties, StepTense tense) {
        return getType().toLowerCase().replace('_', ' ');
    }
}
