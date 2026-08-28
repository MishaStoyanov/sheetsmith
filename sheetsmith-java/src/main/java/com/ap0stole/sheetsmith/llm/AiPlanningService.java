package com.ap0stole.sheetsmith.llm;

import com.ap0stole.sheetsmith.domain.dto.LlmSettingsDto;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import com.ap0stole.sheetsmith.requests.AutomationRequest;
import com.ap0stole.sheetsmith.services.LlmSettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class AiPlanningService {

    private static final String PROMPT_HEADER = """
            You are an Excel Automation Expert. Generate ONLY a valid JSON object.
            NO comments, NO markdown formatting, NO extra text.

            STRICT JSON STRUCTURE:
            {
              "steps": [
                {
                  "type": "ACTION_TYPE",
                  ... action-specific keys ...
                }
              ]
            }

            """;

    private static final String PROMPT_RULES = """
            STRICT RULES:
            - "type" MUST be inside each object in the "steps" array
            - NEVER put "type" at the root level
            - Use ONLY the keys listed above for each action
            - Return ONLY the JSON object, nothing else
            - Use "sheetName" (exact match) when you know the sheet name; use "sheetIndex" otherwise
            """;

    private final LlmSettingsService llmSettingsService;
    private final LlmClientFactory llmClientFactory;
    private final ObjectMapper objectMapper;

    /** Built once at startup: the action list depends on which transform beans exist. */
    private final String systemPrompt;

    public AiPlanningService(LlmSettingsService llmSettingsService, LlmClientFactory llmClientFactory,
                             ObjectMapper objectMapper, ActionCatalogPrompt catalog) {
        this.llmSettingsService = llmSettingsService;
        this.llmClientFactory = llmClientFactory;
        this.objectMapper = objectMapper;
        this.systemPrompt = PROMPT_HEADER
                + ActionCatalog.SHEET_TARGETING
                + "\nSUPPORTED ACTIONS:\n\n"
                + catalog.mutatingActions()
                + "\n"
                + ActionCatalog.COLOR_REFERENCE
                + "\n"
                + PROMPT_RULES;
    }

    public PlanningResult generatePlan(String instruction, String tableContext) {
        String userMessage = "Table Context:\n" + tableContext + "\n\nUser Request: " + instruction;
        return callAndParse(userMessage);
    }

    public PlanningResult fixPlan(String instruction, String errors, String tableContext) {
        String userMessage = """
                Table Context:
                %s

                User Request: %s

                Previous attempt FAILED with these errors:
                %s

                Generate a corrected plan that avoids these errors.
                """.formatted(tableContext, instruction, errors);
        return callAndParse(userMessage);
    }

    /**
     * Asks for the whole response rather than just its text: the token count lives in the response
     * metadata, and a plan whose cost was thrown away here cannot be attributed to the run later.
     */
    private PlanningResult callAndParse(String userMessage) {
        LlmSettingsDto settings = llmSettingsService.active();
        ChatModel chatModel = llmClientFactory.getChatModel(settings);
        ChatClient chatClient = ChatClient.builder(chatModel).defaultSystem(systemPrompt).build();

        ChatResponse response;
        try {
            response = chatClient.prompt()
                    .user(userMessage)
                    .call()
                    .chatResponse();
        } catch (Exception e) {
            log.error("LLM call failed", e);
            throw new ApiException(ErrorCode.LLM_FAILURE, LlmFailures.humanize(e));
        }

        String raw = textOf(response);
        if (raw == null || raw.isBlank()) {
            log.warn("LLM returned empty response");
            throw new ApiException(ErrorCode.LLM_FAILURE, "The AI returned an empty response — try again");
        }

        TokenUsage usage = TokenUsage.from(response);
        LlmEngine engine = LlmEngine.of(settings);
        try {
            String cleaned = extractJson(raw);
            log.debug("Parsed LLM JSON: {}", cleaned);
            return new PlanningResult(objectMapper.readValue(cleaned, AutomationRequest.class), usage, engine);
        } catch (Exception e) {
            log.error("Failed to parse LLM response as JSON: {}", raw, e);
            throw new ApiException(ErrorCode.LLM_FAILURE, "The AI returned an invalid response — try rephrasing your instruction");
        }
    }

    private static String textOf(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start == -1 || end == -1) {
            log.warn("No JSON object found in LLM response: {}", raw);
            return "{}";
        }
        String json = raw.substring(start, end + 1);
        // strip single-line and multi-line comments that some models add
        return json.replaceAll("//[^\n]*", "").replaceAll("/\\*.*?\\*/", "");
    }
}
