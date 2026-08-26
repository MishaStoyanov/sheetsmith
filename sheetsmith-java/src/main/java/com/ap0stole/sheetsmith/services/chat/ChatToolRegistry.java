package com.ap0stole.sheetsmith.services.chat;

import com.ap0stole.sheetsmith.configs.ConditionalOnChatEnabled;
import com.ap0stole.sheetsmith.llm.ActionCatalog;
import com.ap0stole.sheetsmith.llm.ActionCatalogPrompt;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.ActionRegistry;
import com.ap0stole.sheetsmith.services.excel.query.QueryResult;
import com.ap0stole.sheetsmith.services.excel.query.QueryTool;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The chat's single dispatch point over both tool families: the mutating {@link ActionHandler}s
 * the improve pipeline already uses, and the read-only {@link QueryTool}s added for chat.
 * <p>
 * Adding an action to the improve flow therefore makes it available in chat for free.
 */
@Slf4j
@Service
@ConditionalOnChatEnabled
public class ChatToolRegistry {

    private final ActionRegistry actionRegistry;
    private final ActionCatalogPrompt catalog;
    private final Map<String, QueryTool> queryTools;

    public ChatToolRegistry(ActionRegistry actionRegistry, ActionCatalogPrompt catalog, List<QueryTool> tools) {
        this.actionRegistry = actionRegistry;
        this.catalog = catalog;
        this.queryTools = tools.stream()
                .sorted(Comparator.comparing(QueryTool::getType))
                .collect(Collectors.toMap(t -> t.getType().toUpperCase(), Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
        log.info("Chat tools registered: {} actions, {} queries", actionRegistry.size(), queryTools.size());
    }

    public boolean isKnown(String tool) {
        return actionRegistry.find(tool) != null || queryTools.containsKey(normalize(tool));
    }

    public boolean isMutating(String tool) {
        return actionRegistry.find(tool) != null;
    }

    public ToolInvocation invoke(XSSFWorkbook workbook, String tool, Map<String, Object> args) {
        String key = normalize(tool);
        Map<String, Object> safeArgs = args == null ? Map.of() : args;

        QueryTool query = queryTools.get(key);
        if (query != null) {
            return runQuery(workbook, query, safeArgs);
        }

        ActionHandler action = actionRegistry.find(key);
        if (action != null) {
            return runAction(workbook, action, safeArgs);
        }

        log.warn("Chat requested unknown tool '{}'", tool);
        return ToolInvocation.failed(key, false, "Tried to use an unavailable tool",
                "Unknown tool '" + key + "'. Available tools: " + String.join(", ", availableTools()));
    }

    /** Tool names for error messages — the model uses these to correct itself. */
    public List<String> availableTools() {
        return java.util.stream.Stream.concat(actionRegistry.types().stream(), queryTools.keySet().stream())
                .sorted()
                .toList();
    }

    /**
     * The tool section of the chat system prompt: the shared action catalog plus every query
     * tool's own spec, so a new tool documents itself simply by existing as a bean.
     *
     * @param fullActionRules false for the compact action index — most chat turns are questions and
     *                        never need the detailed editing rules, which are the bulk of the prompt
     */
    public String toolCatalogPrompt(boolean fullActionRules) {
        StringBuilder sb = new StringBuilder();
        sb.append("ACTION TOOLS — these CHANGE the sheet:\n\n");
        if (fullActionRules) {
            sb.append(catalog.mutatingActions());
            sb.append("\n").append(ActionCatalog.SHEET_TARGETING);
            sb.append("\n").append(ActionCatalog.COLOR_REFERENCE);
        } else {
            sb.append(catalog.mutatingActionsIndex());
        }
        sb.append("\nQUERY TOOLS — these only READ, and are how you learn anything about the data:\n\n");

        int index = actionRegistry.size();
        for (QueryTool tool : queryTools.values()) {
            sb.append(++index).append(". ").append(tool.promptSpec().strip()).append("\n\n");
        }
        return sb.toString().strip();
    }

    private ToolInvocation runQuery(XSSFWorkbook workbook, QueryTool tool, Map<String, Object> args) {
        try {
            QueryResult result = tool.execute(workbook, args);
            return ToolInvocation.ok(tool.getType(), false, tool.describe(args), result.summary(), result.data());
        } catch (Exception e) {
            log.warn("Query tool {} failed: {}", tool.getType(), e.getMessage());
            return ToolInvocation.failed(tool.getType(), false, tool.describe(args), errorText(e));
        }
    }

    private ToolInvocation runAction(XSSFWorkbook workbook, ActionHandler action, Map<String, Object> args) {
        String description = actionRegistry.describe(action.getType(), args);
        try {
            // An action that only partly succeeded says so here; the model has to see that in its
            // trace, or it will answer "done" over a column it converted half of.
            String detail = action.execute(workbook, args);
            boolean hasDetail = detail != null && !detail.isBlank();
            String outcome = hasDetail ? detail : "applied";
            String humanText = hasDetail ? description + " — " + detail : description;
            return ToolInvocation.ok(action.getType(), true, humanText, outcome, outcome);
        } catch (Exception e) {
            log.warn("Action {} failed in chat: {}", action.getType(), e.getMessage());
            return ToolInvocation.failed(action.getType(), true, description, errorText(e));
        }
    }

    private String errorText(Exception e) {
        String message = e.getMessage();
        return (message == null || message.isBlank()) ? e.getClass().getSimpleName() : message;
    }

    private String normalize(String tool) {
        return tool == null ? "" : tool.trim().toUpperCase();
    }
}
