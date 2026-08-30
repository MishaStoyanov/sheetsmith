package com.ap0stole.sheetsmith.services.chat;

import com.ap0stole.sheetsmith.configs.ConditionalOnChatEnabled;
import com.ap0stole.sheetsmith.configs.ChatConfig;
import com.ap0stole.sheetsmith.domain.dto.chat.ChatMessageDto;
import com.ap0stole.sheetsmith.domain.dto.chat.ChatStepDto;
import com.ap0stole.sheetsmith.domain.dto.chat.ChatTurnDto;
import com.ap0stole.sheetsmith.domain.entity.ChatMessage;
import com.ap0stole.sheetsmith.domain.entity.DocumentSession;
import com.ap0stole.sheetsmith.domain.entity.ChatStep;
import com.ap0stole.sheetsmith.domain.enums.ChatRole;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import com.ap0stole.sheetsmith.domain.entity.User;
import java.time.LocalDateTime;
import com.ap0stole.sheetsmith.llm.AgentDecision;
import com.ap0stole.sheetsmith.llm.ChatCall;
import com.ap0stole.sheetsmith.services.UsageRecorder;
import com.ap0stole.sheetsmith.llm.ChatLlmService;
import com.ap0stole.sheetsmith.services.DocumentSessionService;
import com.ap0stole.sheetsmith.services.SessionLockRegistry;
import com.ap0stole.sheetsmith.services.excel.FormulaErrorScanner;
import com.ap0stole.sheetsmith.services.excel.FormulaErrorScanner.CellError;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The chat agent loop: the model picks one tool at a time, Java runs it against the session's
 * working copy, and only the small result goes back into the conversation. The full table is
 * never sent anywhere — that is the whole point of the design.
 * <p>
 * A turn that edited the sheet also checks its own work: cells the edit turned into Excel errors
 * are fed back to the model for a bounded repair attempt, and whatever is still broken is admitted
 * to the user rather than reported as success.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnChatEnabled
public class ChatAgentService {

    /** The prefix every line of a turn's trace starts with. */
    private static final String STEP = "STEP ";

    private static final String FALLBACK_ANSWER =
            "I couldn't finish that one — open the steps below to see what I tried.";

    /** Pseudo-tool name for the self-check, so the repair shows up in "how I got there" like any step. */
    private static final String SELF_CHECK_TOOL = "SELF_CHECK";

    private final DocumentSessionService sessionService;
    private final ChatToolRegistry toolRegistry;
    private final ChatLlmService chatLlmService;
    private final ChatConfig chatConfig;
    private final FormulaErrorScanner errorScanner;
    private final ObjectMapper objectMapper;
    private final SessionLockRegistry sessionLocks;
    private final UsageRecorder usageRecorder;
    private final com.ap0stole.sheetsmith.services.BudgetService budgets;

    /** The lock is shared with the improve job, which also appends revisions to this session. */
    public ChatTurnDto send(String sessionId, String text) {
        return send(sessionId, text, TurnListener.NOOP);
    }

    /** Same turn, narrated: the listener hears each tool call as it lands. Used by the SSE endpoint. */
    public ChatTurnDto send(String sessionId, String text, TurnListener listener) {
        // Checked before the session lock is taken. A refusal that first queued behind whoever else
        // is editing the document would make being over budget look like the application hanging.
        budgets.requireHeadroom();
        return sessionLocks.withSession(sessionId, () -> runTurn(sessionId, text, false, listener));
    }

    /**
     * A turn that may look but not touch. Used where the sheet must come back unchanged whatever
     * the model decides — the suggestion pass inspects the data before proposing anything.
     */
    public ChatTurnDto inspect(String sessionId, String text) {
        // A look costs a call like any other. "What would you improve?" spends real tokens, and a
        // budget that only counted the edits would be a budget with a hole in it.
        budgets.requireHeadroom();
        return sessionLocks.withSession(sessionId, () -> runTurn(sessionId, text, true, TurnListener.NOOP));
    }

    private ChatTurnDto runTurn(String sessionId, String text, boolean readOnly, TurnListener listener) {
        DocumentSession session = sessionService.require(sessionId);

        sessionService.touch(session);

        String history = sessionService.recentHistoryPrompt(sessionId, chatConfig.getHistoryMessages());
        sessionService.record(session, ChatRole.USER, text, null);

        // Most turns are questions, so the model starts with the compact action index and only
        // gets the full editing rules if it actually reaches for an action — unless the deployment
        // prefers one stable prompt for the whole turn (see ChatConfig#fullCatalogAlways).
        Turn turn = new Turn(sessionId, text, history, sessionService.tableContext(session),
                toolRegistry.toolCatalogPrompt(chatConfig.isFullCatalogAlways()), listener,
                // Read from the session rather than from the security context: a streamed turn runs
                // on a virtual thread, where the caller is no longer visible.
                session.getUser());
        turn.readOnly = readOnly;

        try (FileInputStream in = new FileInputStream(sessionService.currentPath(session).toFile());
             XSSFWorkbook workbook = new XSSFWorkbook(in)) {

            String answer = runSteps(turn, workbook, chatConfig.getMaxSteps());
            turn.answer = answer != null ? answer : forceAnswer(turn);

            if (turn.mutated) {
                selfHeal(turn, workbook);
                int revision = sessionService.commitRevision(session, workbook);
                return persist(session, turn, revision, true);
            }

        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Chat turn failed for session {}", sessionId, e);
            throw new ApiException(ErrorCode.PROCESSING_ERROR, "Chat failed: " + e.getMessage());
        }

        return persist(session, turn, session.getCurrentRevision(), false);
    }

    /**
     * One pass of the loop, shared by the main turn and the repair attempt: the model picks a tool,
     * Java runs it, the result is appended to the trace it reads next.
     *
     * @return the model's answer, or null if the budget ran out before it gave one
     */
    private String runSteps(Turn turn, XSSFWorkbook workbook, int budget) {
        for (int i = 0; i < budget; i++) {
            turn.stepNumber++;
            AgentDecision decision = decide(turn, false);

            if (refusedAsReadOnly(turn, decision)) {
                continue;
            }
            decision = escalateIfEditing(turn, workbook, decision);

            if (decision.isAnswer()) {
                return decision.answer();
            }
            if (!decision.isToolCall()) {
                rejectUnusableReply(turn, decision);
                continue;
            }

            ToolInvocation invocation = toolRegistry.invoke(workbook, decision.tool(), decision.args());
            turn.add(invocation, decision.args());
            turn.mutated = turn.mutated || (invocation.mutating() && invocation.success());
            appendTrace(turn, decision, invocation);
        }
        return null;
    }

    /**
     * A read-only turn refuses the tool rather than the whole turn, so the model can correct itself
     * instead of the caller getting a sheet they never asked to change.
     *
     * @return true when the step was refused and the turn should try again
     */
    private boolean refusedAsReadOnly(Turn turn, AgentDecision decision) {
        if (!turn.readOnly || !decision.isToolCall() || !toolRegistry.isMutating(decision.tool())) {
            return false;
        }
        turn.trace.append(STEP).append(turn.stepNumber).append(": REFUSED — ")
                .append(decision.tool())
                .append(" changes the sheet, and this is a look-only pass. ")
                .append("Use query tools, then answer.\n");
        return true;
    }

    /**
     * The compact index is enough to pick an action but not to get its details right, so the model
     * is asked once more with the full rules before anything touches the sheet.
     * <p>
     * When the rules were in front of it all along there is nothing to re-ask — but the pass still
     * has to happen, because it is also where the self-check takes its "before" snapshot.
     *
     * @return the decision to act on: a fresh one where the prompt grew, the original otherwise
     */
    private AgentDecision escalateIfEditing(Turn turn, XSSFWorkbook workbook, AgentDecision decision) {
        boolean firstEdit = decision.isToolCall() && !turn.escalated
                && toolRegistry.isMutating(decision.tool());
        if (firstEdit && escalateToEditing(turn, workbook)) {
            return decide(turn, false);
        }
        return decision;
    }

    /** Not an answer and not a tool call: say what was wrong with it and let the model try again. */
    private void rejectUnusableReply(Turn turn, AgentDecision decision) {
        log.warn("Chat session {} got an unusable reply: {}", turn.sessionId, decision.parseError());
        turn.trace.append(STEP).append(turn.stepNumber).append(": REJECTED — ")
                .append(decision.parseError())
                .append(" Reply with a single JSON object.\n");
    }

    /**
     * The turn just became an editing turn. Besides upgrading the prompt, this is the one moment
     * worth scanning for pre-existing errors: question turns never reach it and so never pay.
     * <p>
     * The error snapshot is taken either way — without it {@link #selfHeal} has no baseline and
     * would blame the user's own broken cells on this turn, or skip the check entirely.
     *
     * @return true when the prompt actually grew, meaning the step has to be asked again
     */
    private boolean escalateToEditing(Turn turn, XSSFWorkbook workbook) {
        turn.escalated = true;
        turn.errorsBefore = errorScanner.scan(workbook);

        if (chatConfig.isFullCatalogAlways()) {
            return false;
        }
        turn.catalog = toolRegistry.toolCatalogPrompt(true);
        return true;
    }

    /**
     * Cells this turn broke — errors that were not there before the first edit — are named back to
     * the model, which gets {@code repair-steps} more tool calls to put them right. Anything still
     * broken afterwards is admitted in the answer: we never claim success over a broken sheet.
     */
    private void selfHeal(Turn turn, XSSFWorkbook workbook) {
        // errorsBefore is null when no edit was announced through the escalation point: without a
        // baseline, every error would look new and the user's own would be blamed on us.
        if (turn.errorsBefore == null || chatConfig.getRepairSteps() <= 0) {
            return;
        }

        List<CellError> broken = FormulaErrorScanner.newErrors(turn.errorsBefore, errorScanner.scan(workbook));
        if (broken.isEmpty()) {
            return;
        }

        log.warn("Chat session {} introduced {} broken cell(s): {}", turn.sessionId, broken.size(), list(broken));
        turn.trace.append("SELF-CHECK: your edit left ").append(list(broken))
                .append(". Fix ").append(broken.size() == 1 ? "it" : "them")
                .append(" with further tool calls, then answer.\n");

        int mark = turn.invocations.size();
        runSteps(turn, workbook, chatConfig.getRepairSteps());

        List<CellError> remaining = FormulaErrorScanner.newErrors(turn.errorsBefore, errorScanner.scan(workbook));
        turn.insert(mark, selfCheckStep(broken, remaining), Map.of("cells", labels(broken)));

        if (!remaining.isEmpty()) {
            turn.answer = turn.answer + "\n\nHeads-up: " + list(remaining)
                    + " — I could not repair " + (remaining.size() == 1 ? "it" : "them")
                    + ", so please check " + (remaining.size() == 1 ? "that cell" : "those cells") + ".";
        }
    }

    /** The repair, as one entry in the step chain — inserted ahead of the steps it triggered. */
    private ToolInvocation selfCheckStep(List<CellError> broken, List<CellError> remaining) {
        String found = "Spotted " + list(broken) + " after my edit";
        if (remaining.isEmpty()) {
            return ToolInvocation.ok(SELF_CHECK_TOOL, false,
                    found + " and repaired " + (broken.size() == 1 ? "it" : "them"), "repaired", "repaired");
        }
        return ToolInvocation.failed(SELF_CHECK_TOOL, false, found + " — still broken after the repair attempt",
                list(remaining) + " could not be repaired");
    }

    /** Last chance for the model to summarise what it found before we give up on the turn. */
    private String forceAnswer(Turn turn) {
        try {
            AgentDecision decision = decide(turn, true);
            return decision.isAnswer() ? decision.answer() : FALLBACK_ANSWER;
        } catch (Exception e) {
            log.warn("Final answer call failed: {}", e.getMessage());
            return FALLBACK_ANSWER;
        }
    }

    /**
     * One step of the turn, and the record of what it cost.
     * <p>
     * Recorded here rather than around the loop because a turn is many calls: the model is asked
     * once per step, plus once more for the forced answer, and each one is money. Counting only the
     * turn would undercount every turn that used a tool.
     */
    private AgentDecision decide(Turn turn, boolean mustAnswer) {
        LocalDateTime startedAt = LocalDateTime.now();
        ChatCall call = chatLlmService.decide(turn.catalog, turn.tableContext, turn.history,
                turn.userText, turn.trace.toString(), mustAnswer);

        usageRecorder.chat(turn.sessionId, turn.owner, turn.userText,
                call.usage(), call.engine(), startedAt);
        return call.decision();
    }

    private void appendTrace(Turn turn, AgentDecision decision, ToolInvocation invocation) {
        turn.trace.append(STEP).append(turn.stepNumber).append(": ").append(invocation.tool())
                .append(' ').append(writeArgs(decision.args())).append(" → ");
        if (invocation.success()) {
            turn.trace.append(invocation.mutating()
                    ? invocation.resultPreview()
                    : chatLlmService.renderResult(invocation.data()));
        } else {
            turn.trace.append("ERROR: ").append(invocation.error());
        }
        turn.trace.append('\n');
    }

    private ChatTurnDto persist(DocumentSession session, Turn turn, int revision, boolean mutated) {

        ChatMessage message = sessionService.record(session, ChatRole.ASSISTANT, turn.answer,
                mutated ? revision : null);

        List<ChatStep> steps = new ArrayList<>();
        for (int i = 0; i < turn.invocations.size(); i++) {
            ToolInvocation invocation = turn.invocations.get(i);
            ChatStep step = ChatStep.of(message, i, invocation.tool(), invocation.mutating());
            step.setHumanText(invocation.humanText());
            step.setArgsJson(writeArgs(turn.args.get(i)));
            step.setResultPreview(invocation.resultPreview());
            step.setSuccess(invocation.success());
            step.setErrorMessage(invocation.error());
            steps.add(step);
        }
        sessionService.saveSteps(steps);

        List<ChatStepDto> stepDtos = steps.stream().map(ChatStepDto::from).toList();
        return new ChatTurnDto(ChatMessageDto.from(message, stepDtos), mutated, revision);
    }

    private String list(List<CellError> errors) {
        return String.join(", ", labels(errors));
    }

    private List<String> labels(List<CellError> errors) {
        return errors.stream().map(CellError::label).toList();
    }

    private String writeArgs(Map<String, Object> args) {
        try {
            return objectMapper.writeValueAsString(args == null ? Map.of() : args);
        } catch (Exception _) {
            return "{}";
        }
    }

    /** Everything a single turn accumulates, so the main pass and the repair pass share one state. */
    private static final class Turn {

        private final String sessionId;
        private final String userText;
        private final String history;
        private final String tableContext;
        private final List<ToolInvocation> invocations = new ArrayList<>();
        private final List<Map<String, Object>> args = new ArrayList<>();
        private final StringBuilder trace = new StringBuilder();
        private final TurnListener listener;
        private final User owner;

        private String catalog;
        private String answer;
        /** The one-time editing pass has run — the rules are up and the error baseline is taken. */
        private boolean escalated;
        private boolean mutated;
        private boolean readOnly;
        private int stepNumber;
        private int emitted;

        /** Errors already present when the turn first reached for an action; null on question turns. */
        private List<CellError> errorsBefore;

        private Turn(String sessionId, String userText, String history, String tableContext,
                     String catalog, TurnListener listener, User owner) {
            this.sessionId = sessionId;
            this.userText = userText;
            this.history = history;
            this.tableContext = tableContext;
            this.catalog = catalog;
            this.listener = listener;
            this.owner = owner;
        }

        private void add(ToolInvocation invocation, Map<String, Object> callArgs) {
            invocations.add(invocation);
            args.add(callArgs);
            emit(invocation);
        }

        private void insert(int index, ToolInvocation invocation, Map<String, Object> callArgs) {
            invocations.add(index, invocation);
            args.add(index, callArgs);
            emit(invocation);
        }

        /** A listener that has gone away — a browser that closed the stream — must not fail the turn. */
        private void emit(ToolInvocation invocation) {
            try {
                listener.onStep(invocation, emitted++);
            } catch (Exception e) {
                log.debug("Turn listener rejected step {}: {}", emitted - 1, e.getMessage());
            }
        }
    }
}
