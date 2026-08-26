package com.ap0stole.sheetsmith.services.chat;

import com.ap0stole.sheetsmith.configs.ConditionalOnChatEnabled;
import com.ap0stole.sheetsmith.domain.dto.PlanRequest;
import com.ap0stole.sheetsmith.domain.dto.PlanResponseDto;
import com.ap0stole.sheetsmith.domain.dto.chat.ChatTurnDto;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import com.ap0stole.sheetsmith.services.JobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Answers "what would you improve?" for a user who has nothing to type yet.
 * <p>
 * The suggestions are grounded rather than generic because the sheet is inspected first: a read-only
 * agent pass runs query tools over the actual data, and its findings become the instruction the
 * planner turns into reviewable steps. The user gets "column C has 12 blanks", not "bold the header".
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnChatEnabled
public class SuggestionService {

    private static final String INSPECTION_PROMPT = """
            Look over this sheet and tell me the three most valuable concrete improvements to make.

            Inspect the data first with the query tools — check what the columns actually hold, where
            values are missing, whether numbers are stored as text, whether a totals row exists.
            Base every suggestion on something you measured, and name the exact ranges, cells and
            columns involved so the change can be applied without guessing.

            Do not change anything. Answer with the three improvements, one sentence each.
            """;

    private final ChatAgentService chatAgentService;
    private final JobService jobService;

    /** Matches PlanRequest's own limit, so a rambling inspection cannot produce an invalid plan request. */
    private static final int MAX_INSTRUCTION = 2000;

    public PlanResponseDto suggest(String sessionId) {
        ChatTurnDto inspection = chatAgentService.inspect(sessionId, INSPECTION_PROMPT);
        String findings = inspection.message().content();

        if (findings == null || findings.isBlank()) {
            throw new ApiException(ErrorCode.LLM_FAILURE,
                    "The assistant could not read enough of this sheet to suggest anything — try describing what you want instead.");
        }
        if (findings.length() > MAX_INSTRUCTION) {
            findings = findings.substring(0, MAX_INSTRUCTION);
        }

        log.info("Suggestion pass for session {} inspected the sheet in {} step(s)",
                sessionId, inspection.message().steps().size());

        // The findings become the instruction, so suggestions land in the same review cards and
        // apply through the same path as anything the user typed themselves.
        return jobService.generatePlan(new PlanRequest(sessionId, findings));
    }
}
