package com.ap0stole.sheetsmith.services.chat;

import com.ap0stole.sheetsmith.configs.ChatConfig;
import com.ap0stole.sheetsmith.domain.dto.chat.ChatStepDto;
import com.ap0stole.sheetsmith.domain.dto.chat.ChatTurnDto;
import com.ap0stole.sheetsmith.domain.entity.ChatMessage;
import com.ap0stole.sheetsmith.domain.entity.ChatSession;
import com.ap0stole.sheetsmith.domain.enums.ChatRole;
import com.ap0stole.sheetsmith.llm.AgentDecision;
import com.ap0stole.sheetsmith.llm.ChatLlmService;
import com.ap0stole.sheetsmith.services.SessionLockRegistry;
import com.ap0stole.sheetsmith.services.excel.FormulaErrorScanner;
import com.ap0stole.sheetsmith.services.excel.FormulaErrorScanner.CellError;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Exercises the loop itself with a scripted model: the LLM and the tools are stubbed, so what is
 * under test is the step budget, the mutation bookkeeping and how failures are recorded.
 */
class ChatAgentServiceTest {

    private static final String SESSION_ID = "session-1";
    private static final CellError DIV_ZERO = new CellError("Sales", "C4", "#DIV/0!");

    private ChatSessionService sessionService;
    private ChatToolRegistry toolRegistry;
    private ChatLlmService chatLlmService;
    private ChatConfig chatConfig;
    private FormulaErrorScanner errorScanner;
    private ChatAgentService agent;

    private ChatSession session;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        Path workbookPath = tempDir.resolve("rev-0.xlsx");
        writeWorkbook(workbookPath);

        session = ChatSession.create("sales.xlsx", tempDir.toString());

        sessionService = mock(ChatSessionService.class);
        toolRegistry = mock(ChatToolRegistry.class);
        chatLlmService = mock(ChatLlmService.class);
        errorScanner = mock(FormulaErrorScanner.class);
        chatConfig = new ChatConfig();

        when(sessionService.require(SESSION_ID)).thenReturn(session);
        when(sessionService.recentHistoryPrompt(anyString(), anyInt())).thenReturn("");
        when(sessionService.tableContext(session)).thenReturn("Sheet 0: \"Sales\"");
        when(sessionService.currentPath(session)).thenReturn(workbookPath);
        when(sessionService.record(any(), any(), anyString(), any())).thenAnswer(call -> {
            ChatMessage message = ChatMessage.of(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            message.setRevisionAfter(call.getArgument(3));
            return message;
        });
        when(toolRegistry.toolCatalogPrompt(anyBoolean())).thenReturn("TOOLS");
        when(chatLlmService.renderResult(any())).thenReturn("{\"value\":1240}");

        agent = new ChatAgentService(sessionService, toolRegistry, chatLlmService, chatConfig,
                errorScanner, new ObjectMapper(), new SessionLockRegistry());
    }

    @Test
    @DisplayName("a question runs query tools and answers without touching the sheet")
    void answersWithoutMutating() throws Exception {
        when(chatLlmService.decide(any(), any(), any(), any(), any(), eq(false)))
                .thenReturn(AgentDecision.toolCall("AGGREGATE", Map.of("range", "A2:B4")))
                .thenReturn(AgentDecision.answer("Widget A sold most — 1240."));
        when(toolRegistry.invoke(any(), eq("AGGREGATE"), any()))
                .thenReturn(ToolInvocation.ok("AGGREGATE", false,
                        "Summed column B over A2:B4", "1240", Map.of("value", 1240)));

        ChatTurnDto turn = agent.send(SESSION_ID, "What sold most?");

        assertThat(turn.mutated()).isFalse();
        assertThat(turn.message().content()).isEqualTo("Widget A sold most — 1240.");
        assertThat(turn.message().steps()).singleElement()
                .satisfies(step -> {
                    assertThat(step.text()).isEqualTo("Summed column B over A2:B4");
                    assertThat(step.mutating()).isFalse();
                    assertThat(step.success()).isTrue();
                });
        verify(sessionService, never()).commitRevision(any(), any());
    }

    @Test
    @DisplayName("a successful action is committed as a new revision")
    void commitsRevisionAfterMutation() throws Exception {
        AgentDecision sort = AgentDecision.toolCall("SORT_DATA", Map.of("range", "A2:B4", "columnIndex", 1));
        when(toolRegistry.isMutating("SORT_DATA")).thenReturn(true);
        when(chatLlmService.decide(any(), any(), any(), any(), any(), eq(false)))
                .thenReturn(sort)                                  // chosen from the compact index
                .thenReturn(sort)                                  // restated with the full rules
                .thenReturn(AgentDecision.answer("Sorted by revenue."));
        when(toolRegistry.invoke(any(), eq("SORT_DATA"), any()))
                .thenReturn(ToolInvocation.ok("SORT_DATA", true,
                        "Sorted A2:B4 by column B, highest first", "applied", "applied"));
        when(sessionService.commitRevision(eq(session), any())).thenReturn(1);

        ChatTurnDto turn = agent.send(SESSION_ID, "sort by revenue");

        assertThat(turn.mutated()).isTrue();
        assertThat(turn.revision()).isEqualTo(1);
        assertThat(turn.message().revisionAfter()).isEqualTo(1);
        assertThat(turn.message().steps()).hasSize(1);
        verify(sessionService).commitRevision(eq(session), any());
    }

    @Test
    @DisplayName("a look-only pass refuses a mutating tool instead of changing the sheet")
    void inspectRefusesMutations() throws Exception {
        when(toolRegistry.isMutating("SORT_DATA")).thenReturn(true);
        when(chatLlmService.decide(any(), any(), any(), any(), any(), eq(false)))
                .thenReturn(AgentDecision.toolCall("SORT_DATA", Map.of("range", "A2:B4")))
                .thenReturn(AgentDecision.answer("Column B is unsorted and has 2 blanks."));

        ChatTurnDto turn = agent.inspect(SESSION_ID, "what would you improve?");

        assertThat(turn.mutated()).isFalse();
        assertThat(turn.message().content()).isEqualTo("Column B is unsorted and has 2 blanks.");
        assertThat(turn.message().steps()).isEmpty();
        verify(toolRegistry, never()).invoke(any(), eq("SORT_DATA"), any());
        verify(sessionService, never()).commitRevision(any(), any());
    }

    @Test
    @DisplayName("a question never pays for the full editing rules")
    void questionsKeepTheCompactCatalog() {
        when(chatLlmService.decide(any(), any(), any(), any(), any(), eq(false)))
                .thenReturn(AgentDecision.toolCall("AGGREGATE", Map.of("range", "A2:B4")))
                .thenReturn(AgentDecision.answer("1240."));
        when(toolRegistry.invoke(any(), eq("AGGREGATE"), any()))
                .thenReturn(ToolInvocation.ok("AGGREGATE", false, "Summed column B", "1240", Map.of()));

        agent.send(SESSION_ID, "what is the total?");

        verify(toolRegistry).toolCatalogPrompt(false);
        verify(toolRegistry, never()).toolCatalogPrompt(true);
    }

    @Test
    @DisplayName("reaching for an action upgrades the prompt to the full rules exactly once")
    void editingUpgradesTheCatalogOnce() throws Exception {
        AgentDecision clear = AgentDecision.toolCall("CLEAR_CELLS", Map.of("range", "B12"));
        when(toolRegistry.isMutating("CLEAR_CELLS")).thenReturn(true);
        when(chatLlmService.decide(any(), any(), any(), any(), any(), eq(false)))
                .thenReturn(clear)   // picked from the compact index
                .thenReturn(clear)   // restated with the full rules
                .thenReturn(clear)   // a second action needs no further upgrade
                .thenReturn(AgentDecision.answer("Cleared."));
        when(toolRegistry.invoke(any(), eq("CLEAR_CELLS"), any()))
                .thenReturn(ToolInvocation.ok("CLEAR_CELLS", true, "Cleared B12", "applied", "applied"));
        when(sessionService.commitRevision(eq(session), any())).thenReturn(1);

        ChatTurnDto turn = agent.send(SESSION_ID, "clear B12 and B13");

        assertThat(turn.message().steps()).hasSize(2);
        verify(toolRegistry, times(1)).toolCatalogPrompt(true);
    }

    @Test
    @DisplayName("with the full catalog up front, an editing turn saves the restatement round trip")
    void fullCatalogAlwaysSkipsTheRestatement() throws Exception {
        chatConfig.setFullCatalogAlways(true);
        AgentDecision sort = AgentDecision.toolCall("SORT_DATA", Map.of("range", "A2:B4", "columnIndex", 1));
        when(toolRegistry.isMutating("SORT_DATA")).thenReturn(true);
        when(chatLlmService.decide(any(), any(), any(), any(), any(), eq(false)))
                .thenReturn(sort)                                  // the rules were already in front of it
                .thenReturn(AgentDecision.answer("Sorted by revenue."));
        when(toolRegistry.invoke(any(), eq("SORT_DATA"), any()))
                .thenReturn(ToolInvocation.ok("SORT_DATA", true,
                        "Sorted A2:B4 by column B, highest first", "applied", "applied"));
        when(sessionService.commitRevision(eq(session), any())).thenReturn(1);

        ChatTurnDto turn = agent.send(SESSION_ID, "sort by revenue");

        assertThat(turn.mutated()).isTrue();
        assertThat(turn.message().steps()).hasSize(1);
        // Two calls rather than the three the escalating path needs for the same work.
        verify(chatLlmService, times(2)).decide(any(), any(), any(), any(), any(), eq(false));
        verify(toolRegistry).toolCatalogPrompt(true);
        verify(toolRegistry, never()).toolCatalogPrompt(false);
    }

    @Test
    @DisplayName("skipping the restatement must not cost the self-check its baseline")
    void fullCatalogAlwaysKeepsTheSelfCheck() throws Exception {
        chatConfig.setFullCatalogAlways(true);
        AgentDecision formula = AgentDecision.toolCall("ADD_FORMULA", Map.of("range", "C2:C4"));
        when(toolRegistry.isMutating(anyString())).thenReturn(true);
        when(chatLlmService.decide(any(), any(), any(), any(), any(), eq(false)))
                .thenReturn(formula)
                .thenReturn(AgentDecision.answer("Added the margin column."))
                .thenReturn(formula)                                        // the repair attempt
                .thenReturn(AgentDecision.answer("Repaired."));
        when(toolRegistry.invoke(any(), eq("ADD_FORMULA"), any()))
                .thenReturn(ToolInvocation.ok("ADD_FORMULA", true,
                        "Wrote =A2/B2 into C2:C4", "applied", "applied"));
        when(sessionService.commitRevision(eq(session), any())).thenReturn(1);
        when(errorScanner.scan(any())).thenReturn(List.of(), List.of(DIV_ZERO), List.of());

        ChatTurnDto turn = agent.send(SESSION_ID, "add a margin column");

        assertThat(turn.message().steps()).hasSize(3);
        assertThat(turn.message().steps().get(1)).satisfies(step -> {
            assertThat(step.tool()).isEqualTo("SELF_CHECK");
            assertThat(step.text()).contains("Sales!C4 #DIV/0!").contains("repaired");
        });
        // The "before" snapshot is still taken, even though the prompt never changed.
        verify(errorScanner, times(3)).scan(any());
    }

    @Test
    @DisplayName("a failed tool is kept in the chain rather than hidden")
    void recordsFailedSteps() {
        when(chatLlmService.decide(any(), any(), any(), any(), any(), eq(false)))
                .thenReturn(AgentDecision.toolCall("READ_RANGE", Map.of("range", "A1:ZZ9999")))
                .thenReturn(AgentDecision.answer("That range is too big to read at once."));
        when(toolRegistry.invoke(any(), eq("READ_RANGE"), any()))
                .thenReturn(ToolInvocation.failed("READ_RANGE", false,
                        "Read A1:ZZ9999", "Range covers 6759324 cells, limit is 300"));

        ChatTurnDto turn = agent.send(SESSION_ID, "show me everything");

        assertThat(turn.message().steps()).singleElement().satisfies(step -> {
            assertThat(step.success()).isFalse();
            assertThat(step.error()).contains("limit is 300");
        });
    }

    @Test
    @DisplayName("an unusable reply costs a step but does not become one")
    void skipsUnparseableReplies() {
        when(chatLlmService.decide(any(), any(), any(), any(), any(), eq(false)))
                .thenReturn(AgentDecision.unparseable("Your reply was not valid JSON"))
                .thenReturn(AgentDecision.answer("Here you go."));

        ChatTurnDto turn = agent.send(SESSION_ID, "hello");

        assertThat(turn.message().content()).isEqualTo("Here you go.");
        assertThat(turn.message().steps()).isEmpty();
        verify(toolRegistry, never()).invoke(any(), any(), any());
    }

    @Test
    @DisplayName("when the step budget runs out the model is asked for a final answer")
    void forcesAnswerWhenBudgetIsSpent() {
        chatConfig.setMaxSteps(2);
        when(chatLlmService.decide(any(), any(), any(), any(), any(), eq(false)))
                .thenReturn(AgentDecision.toolCall("READ_RANGE", Map.of("range", "A1:B2")));
        when(chatLlmService.decide(any(), any(), any(), any(), any(), eq(true)))
                .thenReturn(AgentDecision.answer("Best I can say is 1240."));
        when(toolRegistry.invoke(any(), eq("READ_RANGE"), any()))
                .thenReturn(ToolInvocation.ok("READ_RANGE", false, "Read A1:B2", "2 rows", Map.of()));

        ChatTurnDto turn = agent.send(SESSION_ID, "keep looking");

        assertThat(turn.message().content()).isEqualTo("Best I can say is 1240.");
        assertThat(turn.message().steps()).hasSize(2);
        verify(chatLlmService, times(2)).decide(any(), any(), any(), any(), any(), eq(false));
        verify(chatLlmService).decide(any(), any(), any(), any(), any(), eq(true));
    }

    @Test
    @DisplayName("the user's message is stored before the model is asked anything")
    void storesUserMessageFirst() {
        when(chatLlmService.decide(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(AgentDecision.answer("Hi."));

        agent.send(SESSION_ID, "hello there");

        verify(sessionService).record(session, ChatRole.USER, "hello there", null);
        verify(sessionService).record(eq(session), eq(ChatRole.ASSISTANT), eq("Hi."), any());
    }

    @Test
    @DisplayName("an edit that breaks a cell gets a repair attempt inside the same turn")
    void repairsCellsItBroke() throws Exception {
        scriptEditThenRepair();
        // clean before the edit, broken after it, clean again once the repair ran
        when(errorScanner.scan(any())).thenReturn(List.of(), List.of(DIV_ZERO), List.of());

        ChatTurnDto turn = agent.send(SESSION_ID, "add a margin column");

        assertThat(turn.mutated()).isTrue();
        assertThat(turn.message().content()).isEqualTo("Added the margin column.");
        assertThat(turn.message().steps()).hasSize(3);
        assertThat(turn.message().steps().get(1)).satisfies(step -> {
            assertThat(step.tool()).isEqualTo("SELF_CHECK");
            assertThat(step.success()).isTrue();
            assertThat(step.text()).contains("Sales!C4 #DIV/0!").contains("repaired");
        });
        assertThat(turn.message().steps().get(2).tool()).isEqualTo("ADD_FORMULA");
        verify(errorScanner, times(3)).scan(any());
    }

    @Test
    @DisplayName("errors that were already there are the user's own and trigger nothing")
    void ignoresPreExistingErrors() throws Exception {
        scriptEditThenRepair();
        when(errorScanner.scan(any())).thenReturn(List.of(DIV_ZERO));

        ChatTurnDto turn = agent.send(SESSION_ID, "add a margin column");

        assertThat(turn.message().content()).isEqualTo("Added the margin column.");
        assertThat(turn.message().steps()).hasSize(1);
        verify(errorScanner, times(2)).scan(any());   // the baseline and the check, never a third
        verify(toolRegistry, times(1)).invoke(any(), eq("ADD_FORMULA"), any());   // no repair pass
    }

    @Test
    @DisplayName("a cell still broken after the repair is admitted in the answer")
    void admitsCellsItCouldNotRepair() throws Exception {
        scriptEditThenRepair();
        when(errorScanner.scan(any())).thenReturn(List.of(), List.of(DIV_ZERO), List.of(DIV_ZERO));

        ChatTurnDto turn = agent.send(SESSION_ID, "add a margin column");

        assertThat(turn.message().content())
                .startsWith("Added the margin column.")
                .contains("Sales!C4 #DIV/0!")
                .contains("could not repair");
        assertThat(turn.message().steps().get(1)).satisfies(step -> {
            assertThat(step.tool()).isEqualTo("SELF_CHECK");
            assertThat(step.success()).isFalse();
            assertThat(step.error()).contains("Sales!C4 #DIV/0!");
        });
    }

    @Test
    @DisplayName("the self-check is off when its budget is zero")
    void skipsRepairWhenDisabled() throws Exception {
        chatConfig.setRepairSteps(0);
        scriptEditThenRepair();
        when(errorScanner.scan(any())).thenReturn(List.of(), List.of(DIV_ZERO));

        ChatTurnDto turn = agent.send(SESSION_ID, "add a margin column");

        assertThat(turn.message().steps()).hasSize(1);
        verify(errorScanner, times(1)).scan(any());
    }

    @Test
    @DisplayName("a question never scans the sheet for errors")
    void questionsNeverScan() {
        when(chatLlmService.decide(any(), any(), any(), any(), any(), eq(false)))
                .thenReturn(AgentDecision.toolCall("AGGREGATE", Map.of("range", "A2:B4")))
                .thenReturn(AgentDecision.answer("1240."));
        when(toolRegistry.invoke(any(), eq("AGGREGATE"), any()))
                .thenReturn(ToolInvocation.ok("AGGREGATE", false, "Summed column B", "1240", Map.of()));

        agent.send(SESSION_ID, "what is the total?");

        verify(errorScanner, never()).scan(any());
    }

    // ── The step listener: the same turn, narrated as it runs ────────────────

    @Test
    @DisplayName("the listener hears every tool call, in order, saying what the stored chain says")
    void listenerMirrorsTheStoredChain() {
        when(chatLlmService.decide(any(), any(), any(), any(), any(), eq(false)))
                .thenReturn(AgentDecision.toolCall("READ_RANGE", Map.of("range", "A1:B4")))
                .thenReturn(AgentDecision.toolCall("AGGREGATE", Map.of("range", "B2:B4")))
                .thenReturn(AgentDecision.answer("1240 in total."));
        when(toolRegistry.invoke(any(), eq("READ_RANGE"), any()))
                .thenReturn(ToolInvocation.ok("READ_RANGE", false, "Read A1:B4", "4 rows", Map.of()));
        when(toolRegistry.invoke(any(), eq("AGGREGATE"), any()))
                .thenReturn(ToolInvocation.ok("AGGREGATE", false, "Summed column B", "1240", Map.of()));

        StepRecorder heard = new StepRecorder();
        ChatTurnDto turn = agent.send(SESSION_ID, "what is the total?", heard);

        assertThat(heard.orders).containsExactly(0, 1);
        assertThat(heard.texts).containsExactly("Read A1:B4", "Summed column B");
        assertThat(turn.message().steps()).extracting(ChatStepDto::text)
                .containsExactlyElementsOf(heard.texts);
        assertThat(turn.message().steps()).extracting(ChatStepDto::tool)
                .containsExactlyElementsOf(heard.tools);
    }

    @Test
    @DisplayName("a failed tool reaches the listener too — a live view that hides errors is a lie")
    void listenerHearsFailedSteps() {
        when(chatLlmService.decide(any(), any(), any(), any(), any(), eq(false)))
                .thenReturn(AgentDecision.toolCall("READ_RANGE", Map.of("range", "A1:ZZ9999")))
                .thenReturn(AgentDecision.answer("That range is too big to read at once."));
        when(toolRegistry.invoke(any(), eq("READ_RANGE"), any()))
                .thenReturn(ToolInvocation.failed("READ_RANGE", false,
                        "Read A1:ZZ9999", "Range covers 6759324 cells, limit is 300"));

        StepRecorder heard = new StepRecorder();
        agent.send(SESSION_ID, "show me everything", heard);

        assertThat(heard.steps).singleElement().satisfies(step -> {
            assertThat(step.success()).isFalse();
            assertThat(step.error()).contains("limit is 300");
        });
    }

    @Test
    @DisplayName("the self-check and its repair calls are announced as they happen")
    void listenerHearsTheSelfHealPass() throws Exception {
        scriptEditThenRepair();
        when(errorScanner.scan(any())).thenReturn(List.of(), List.of(DIV_ZERO), List.of());

        StepRecorder heard = new StepRecorder();
        ChatTurnDto turn = agent.send(SESSION_ID, "add a margin column", heard);

        // Chronological: the self-check can only report an outcome once the repair has run, so it
        // is heard last — while the stored chain files it ahead of the calls it triggered.
        assertThat(heard.tools).containsExactly("ADD_FORMULA", "ADD_FORMULA", "SELF_CHECK");
        assertThat(heard.orders).containsExactly(0, 1, 2);
        assertThat(heard.steps.get(2).text()).contains("Sales!C4 #DIV/0!").contains("repaired");
        assertThat(turn.message().steps()).extracting(ChatStepDto::tool)
                .containsExactly("ADD_FORMULA", "SELF_CHECK", "ADD_FORMULA");
        assertThat(heard.texts).containsExactlyInAnyOrderElementsOf(
                turn.message().steps().stream().map(ChatStepDto::text).toList());
    }

    @Test
    @DisplayName("a listener that blows up is the caller's problem, not the turn's")
    void listenerFailureDoesNotBreakTheTurn() {
        when(chatLlmService.decide(any(), any(), any(), any(), any(), eq(false)))
                .thenReturn(AgentDecision.toolCall("AGGREGATE", Map.of("range", "A2:B4")))
                .thenReturn(AgentDecision.answer("1240."));
        when(toolRegistry.invoke(any(), eq("AGGREGATE"), any()))
                .thenReturn(ToolInvocation.ok("AGGREGATE", false, "Summed column B", "1240", Map.of()));

        ChatTurnDto turn = agent.send(SESSION_ID, "what is the total?", (invocation, order) -> {
            throw new IllegalStateException("the browser went away");
        });

        assertThat(turn.message().content()).isEqualTo("1240.");
        assertThat(turn.message().steps()).hasSize(1);
    }

    /** What a caller heard while the turn ran. */
    private static final class StepRecorder implements TurnListener {

        private final List<ChatStepDto> steps = new ArrayList<>();
        private final List<Integer> orders = new ArrayList<>();
        private final List<String> tools = new ArrayList<>();
        private final List<String> texts = new ArrayList<>();

        @Override
        public void onStep(ToolInvocation invocation, int order) {
            steps.add(ChatStepDto.live(invocation, order));
            orders.add(order);
            tools.add(invocation.tool());
            texts.add(invocation.humanText());
        }
    }

    /** An editing turn followed by a model that reaches for one more tool to put things right. */
    private void scriptEditThenRepair() throws Exception {
        AgentDecision formula = AgentDecision.toolCall("ADD_FORMULA", Map.of("range", "C2:C4"));
        when(toolRegistry.isMutating(anyString())).thenReturn(true);
        when(chatLlmService.decide(any(), any(), any(), any(), any(), eq(false)))
                .thenReturn(formula)                                        // chosen from the compact index
                .thenReturn(formula)                                        // restated with the full rules
                .thenReturn(AgentDecision.answer("Added the margin column."))
                .thenReturn(formula)                                        // the repair attempt
                .thenReturn(AgentDecision.answer("Repaired."));
        when(toolRegistry.invoke(any(), eq("ADD_FORMULA"), any()))
                .thenReturn(ToolInvocation.ok("ADD_FORMULA", true,
                        "Wrote =A2/B2 into C2:C4", "applied", "applied"));
        when(sessionService.commitRevision(eq(session), any())).thenReturn(1);
    }

    private void writeWorkbook(Path path) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             FileOutputStream out = new FileOutputStream(path.toFile())) {
            XSSFSheet sheet = workbook.createSheet("Sales");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Product");
            header.createCell(1).setCellValue("Revenue");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("Widget A");
            row.createCell(1).setCellValue(1240);
            workbook.write(out);
        }
    }
}
