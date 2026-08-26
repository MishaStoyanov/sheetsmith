package com.ap0stole.sheetsmith.llm;

import com.ap0stole.sheetsmith.domain.dto.LlmSettingsDto;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import com.ap0stole.sheetsmith.services.LlmSettingsService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The chat half of the LLM integration: one call per agent step, answered with a single JSON
 * object that either runs a tool or ends the turn.
 * <p>
 * We use a plain JSON protocol rather than provider-native function calling because the app must
 * work identically against small local Ollama models, where native tool calling is unreliable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatLlmService {

    private static final String PROTOCOL = """
            You are a spreadsheet assistant. You work through a strict tool protocol.

            You cannot see the spreadsheet. You are given its structure and the results of tools you
            run — nothing else. To learn ANY value in the sheet you must run a query tool.

            Reply with EXACTLY ONE JSON object and nothing else. Two forms are allowed:
              {"tool": "TOOL_NAME", "args": { ...tool keys... }}   — run one tool, you will get its result
              {"answer": "text shown to the user"}                 — finish the turn

            HOW TO WORK:
            - Run one tool per reply. After each result decide whether to run another or answer.
            - To ANSWER A QUESTION use query tools. Never guess a number: every figure in your answer
              must come from a tool result you actually received.
            - To CHANGE THE SHEET use action tools, then finish with a short answer saying what changed.
            - EVAL_FORMULA is usually the shortest path for anything Excel can compute
              (SUMIF, COUNTIF, VLOOKUP, MAX, INDEX/MATCH...).
            - If a tool returns an error, read it, fix the arguments and try again. If it keeps failing,
              answer the user explaining what you could not do.
            - Answer in the same language the user wrote in. Be short and factual — one or two sentences,
              no markdown headings, no preamble.
            - NEVER put anything outside the single JSON object. No markdown fences, no commentary.
            """;

    private static final int MAX_RESULT_CHARS = 4000;

    private final LlmSettingsService llmSettingsService;
    private final LlmClientFactory llmClientFactory;
    private final ObjectMapper objectMapper;

    /**
     * Asks the model for the next step of the turn.
     *
     * @param forceAnswer when true the model is told the step budget is gone and it must answer now
     */
    public AgentDecision decide(String toolCatalog, String tableContext, String history,
                                String userMessage, String trace, boolean forceAnswer) {

        String system = PROTOCOL + "\nAVAILABLE TOOLS\n\n" + toolCatalog;

        StringBuilder user = new StringBuilder();
        user.append("SHEET STRUCTURE (names and ranges only — no cell values):\n")
                .append(tableContext).append("\n\n");
        if (history != null && !history.isBlank()) {
            user.append("EARLIER IN THIS CONVERSATION:\n").append(history).append("\n\n");
        }
        user.append("USER MESSAGE:\n").append(userMessage).append("\n\n");
        if (trace != null && !trace.isBlank()) {
            user.append("TOOLS YOU HAVE ALREADY RUN FOR THIS MESSAGE:\n").append(trace).append("\n\n");
        }
        user.append(forceAnswer
                ? "Your step budget is used up. Reply now with {\"answer\": \"...\"} based on what you have."
                : "Reply with the next JSON object.");

        String raw = call(system, user.toString());
        return parse(raw);
    }

    private String call(String system, String user) {
        LlmSettingsDto settings = llmSettingsService.getSettings();
        ChatModel chatModel = llmClientFactory.getChatModel(settings);
        ChatClient chatClient = ChatClient.builder(chatModel).defaultSystem(system).build();

        try {
            String raw = chatClient.prompt().user(user).call().content();
            if (raw == null || raw.isBlank()) {
                throw new ApiException(ErrorCode.LLM_FAILURE, "The AI returned an empty response — try again");
            }
            return raw;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Chat LLM call failed", e);
            throw new ApiException(ErrorCode.LLM_FAILURE, LlmFailures.humanize(e));
        }
    }

    /**
     * Tolerant on purpose: small models wrap JSON in prose, rename "tool" to "action", or splash the
     * arguments across the root object. Anything we can still read is better than a failed turn.
     */
    AgentDecision parse(String raw) {
        String json = extractJson(raw);
        if (json == null) {
            return AgentDecision.unparseable("Your reply contained no JSON object.");
        }

        try {
            JsonNode root = objectMapper.readTree(json);

            JsonNode answer = firstOf(root, "answer", "final_answer", "response");
            if (answer != null && answer.isTextual() && !answer.asText().isBlank()) {
                return AgentDecision.answer(answer.asText().trim());
            }

            JsonNode tool = firstOf(root, "tool", "action", "tool_name", "type", "name");
            if (tool != null && tool.isTextual() && !tool.asText().isBlank()) {
                return AgentDecision.toolCall(tool.asText().trim(), readArgs(root));
            }

            return AgentDecision.unparseable(
                    "Your JSON had neither a \"tool\" nor an \"answer\" field.");
        } catch (Exception e) {
            log.warn("Unparseable chat reply: {}", raw);
            return AgentDecision.unparseable("Your reply was not valid JSON: " + e.getMessage());
        }
    }

    private JsonNode firstOf(JsonNode root, String... names) {
        for (String name : names) {
            JsonNode node = root.get(name);
            if (node != null && !node.isNull()) return node;
        }
        return null;
    }

    /** Args may arrive nested under "args"/"arguments"/"properties", or flat at the root. */
    private Map<String, Object> readArgs(JsonNode root) {
        JsonNode nested = firstOf(root, "args", "arguments", "properties", "parameters", "input");
        if (nested != null && nested.isObject()) {
            return objectMapper.convertValue(nested, new TypeReference<Map<String, Object>>() {});
        }

        Map<String, Object> flat = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            if (key.equals("tool") || key.equals("action") || key.equals("tool_name")
                    || key.equals("type") || key.equals("name") || key.equals("thought")
                    || key.equals("reasoning") || key.equals("answer")) {
                continue;
            }
            flat.put(key, objectMapper.convertValue(field.getValue(), Object.class));
        }
        return flat;
    }

    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start == -1 || end == -1 || end <= start) return null;
        return raw.substring(start, end + 1)
                .replaceAll("//[^\n]*", "")
                .replaceAll("/\\*.*?\\*/", "");
    }

    /** Tool results are truncated before they enter the conversation — they must never dominate it. */
    public String renderResult(Object data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            return json.length() <= MAX_RESULT_CHARS
                    ? json
                    : json.substring(0, MAX_RESULT_CHARS) + "…(truncated — narrow your query)";
        } catch (Exception e) {
            return String.valueOf(data);
        }
    }
}
