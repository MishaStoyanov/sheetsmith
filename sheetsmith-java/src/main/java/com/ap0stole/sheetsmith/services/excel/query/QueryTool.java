package com.ap0stole.sheetsmith.services.excel.query;

import com.ap0stole.sheetsmith.services.excel.StepTense;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.util.Map;

/**
 * A read-only counterpart to {@link com.ap0stole.sheetsmith.services.excel.ActionHandler}:
 * answers a question about the sheet without modifying it.
 * <p>
 * This is what lets the chat answer "which product sold most?" without ever sending the whole
 * table to the LLM — the model asks for a computation, Java runs it, and only the small result
 * goes back into the conversation.
 */
public interface QueryTool {

    String getType();

    /**
     * The block describing this tool's keys, injected into the chat system prompt.
     * Follow the numbered style used by the mutating actions in {@code ActionCatalog}.
     */
    String promptSpec();

    QueryResult execute(XSSFWorkbook workbook, Map<String, Object> properties) throws Exception;

    /** The past-tense reading — what the chat's "how I got there" chain shows. */
    default String describe(Map<String, Object> properties) {
        return describe(properties, StepTense.PAST);
    }

    /**
     * Plain-language summary of this step, imperative when it is being proposed and past when it
     * has run. Users must never see raw enums, so override it.
     */
    default String describe(Map<String, Object> properties, StepTense tense) {
        return getType().toLowerCase().replace('_', ' ');
    }
}
