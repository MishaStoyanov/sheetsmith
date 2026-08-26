package com.ap0stole.sheetsmith.services.excel.query;

/**
 * Outcome of a {@link QueryTool}. {@code data} must stay small — it is serialized into the
 * LLM conversation verbatim, and the whole point of the tool protocol is that the model
 * sees results, never the full sheet.
 *
 * @param summary short human sentence for the UI step chain
 * @param data    JSON-serializable payload handed back to the model
 */
public record QueryResult(String summary, Object data) {
}
