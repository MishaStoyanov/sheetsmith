package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.configs.ChatConfig;
import com.ap0stole.sheetsmith.configs.FileStorageConfig;
import com.ap0stole.sheetsmith.domain.dto.ApplyPlanRequest;
import com.ap0stole.sheetsmith.domain.dto.PlanRequest;
import com.ap0stole.sheetsmith.domain.dto.PlanStepDto;
import com.ap0stole.sheetsmith.domain.entity.ActionResult;
import com.ap0stole.sheetsmith.domain.entity.DocumentSession;
import com.ap0stole.sheetsmith.domain.entity.JobRecord;
import com.ap0stole.sheetsmith.domain.enums.JobStatus;
import com.ap0stole.sheetsmith.llm.AgentDecision;
import com.ap0stole.sheetsmith.llm.AiPlanningService;
import com.ap0stole.sheetsmith.llm.ChatLlmService;
import com.ap0stole.sheetsmith.repository.ActionResultRepository;
import com.ap0stole.sheetsmith.repository.ChatMessageRepository;
import com.ap0stole.sheetsmith.repository.DocumentSessionRepository;
import com.ap0stole.sheetsmith.repository.ChatStepRepository;
import com.ap0stole.sheetsmith.repository.JobRepository;
import com.ap0stole.sheetsmith.requests.ActionStep;
import com.ap0stole.sheetsmith.requests.AutomationRequest;
import com.ap0stole.sheetsmith.services.chat.ChatAgentService;
import com.ap0stole.sheetsmith.services.chat.ChatToolRegistry;
import com.ap0stole.sheetsmith.services.chat.ToolInvocation;
import com.ap0stole.sheetsmith.services.excel.ActionRegistry;
import com.ap0stole.sheetsmith.services.excel.ExcelAutomationService;
import com.ap0stole.sheetsmith.services.excel.FormulaErrorScanner;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Both flows append to the same revision chain, so they must not interleave: each writer has to see
 * the other's revision before deriving its own. This runs a chat turn and an improve job at the
 * same session at the same time and insists both edits survive.
 */
class SessionWriterConcurrencyTest {

    private static final String JOB_SHEET = "JobEdit";
    /** Column C: the marker has to keep the header row contiguous or schema extraction trips over the gap. */
    private static final int CHAT_COLUMN = 2;
    private static final String CHAT_MARK = "chat";

    private final Map<Long, JobRecord> jobs = new ConcurrentHashMap<>();
    private final Map<String, DocumentSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong jobIds = new AtomicLong();

    private DocumentSessionService sessionService;
    private ChatAgentService agentService;
    private JobService jobService;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        FileStorageConfig storageConfig = new FileStorageConfig();
        storageConfig.setUploadDir(tempDir.resolve("uploads").toString());
        storageConfig.setResultDir(tempDir.resolve("results").toString());
        storageConfig.setSessionDir(tempDir.resolve("sessions").toString());

        DocumentSessionRepository sessionRepository = mock(DocumentSessionRepository.class);
        when(sessionRepository.save(any())).thenAnswer(call -> {
            DocumentSession session = call.getArgument(0);
            sessions.put(session.getId(), session);
            return session;
        });
        when(sessionRepository.findById(anyString()))
                .thenAnswer(call -> Optional.ofNullable(sessions.get(call.<String>getArgument(0))));

        ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
        when(messageRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(messageRepository.findBySessionIdOrderByIdAsc(anyString())).thenReturn(List.of());

        JobRepository jobRepository = mock(JobRepository.class);
        when(jobRepository.save(any())).thenAnswer(call -> {
            JobRecord job = call.getArgument(0);
            if (job.getId() == null) job.setId(jobIds.incrementAndGet());
            jobs.put(job.getId(), job);
            return job;
        });
        when(jobRepository.findById(any()))
                .thenAnswer(call -> Optional.ofNullable(jobs.get(call.<Long>getArgument(0))));

        ActionResultRepository actionResultRepository = mock(ActionResultRepository.class);
        when(actionResultRepository.saveAll(any())).thenAnswer(call -> call.getArgument(0));

        AiPlanningService planningService = mock(AiPlanningService.class);
        when(planningService.generatePlan(anyString(), anyString())).thenReturn(planWithAddSheet());

        ActionRegistry actionRegistry = mock(ActionRegistry.class);
        when(actionRegistry.describe(anyString(), any(), any(StepTense.class))).thenReturn("Add a sheet");

        // Both writers dawdle inside their critical section, so an unsynchronised pair would overlap.
        ExcelAutomationService automationService = mock(ExcelAutomationService.class);
        when(automationService.applyChanges(anyString(), anyString(), any(), any()))
                .thenAnswer(call -> jobEdit(call.getArgument(0), call.getArgument(1), call.getArgument(3)));

        ChatToolRegistry toolRegistry = mock(ChatToolRegistry.class);
        when(toolRegistry.toolCatalogPrompt(anyBoolean())).thenReturn("TOOLS");
        when(toolRegistry.isMutating(anyString())).thenReturn(true);
        when(toolRegistry.invoke(any(), eq("ADD_FORMULA"), any())).thenAnswer(call -> chatEdit(call.getArgument(0)));

        ChatLlmService chatLlmService = mock(ChatLlmService.class);
        AgentDecision edit = AgentDecision.toolCall("ADD_FORMULA", Map.of("range", "C1"));
        when(chatLlmService.decide(any(), any(), any(), any(), any(), eq(false)))
                .thenReturn(edit)                                   // picked from the compact index
                .thenReturn(edit)                                   // restated with the full rules
                .thenReturn(AgentDecision.answer("Marked the sheet."));

        FormulaErrorScanner errorScanner = mock(FormulaErrorScanner.class);
        when(errorScanner.scan(any())).thenReturn(List.of());

        SchemaExtractorService schemaExtractor = new SchemaExtractorService(new ChatConfig());
        SessionLockRegistry sessionLocks = new SessionLockRegistry();

        sessionService = new DocumentSessionService(storageConfig, sessionRepository, messageRepository,
                mock(ChatStepRepository.class), new SessionSchemaCache(schemaExtractor));
        agentService = new ChatAgentService(sessionService, toolRegistry, chatLlmService, new ChatConfig(),
                errorScanner, new ObjectMapper(), sessionLocks);
        jobService = new JobService(jobRepository, actionResultRepository, new FileStorageService(storageConfig),
                schemaExtractor, planningService, automationService, actionRegistry,
                mock(PathGuard.class), new Semaphore(1), sessionService, sessionLocks);
    }

    @Test
    @DisplayName("a chat turn and an improve job on one session serialise, and both edits survive")
    void chatTurnAndImproveJobDoNotInterleave() throws Exception {
        DocumentSession session = openSession();
        CountDownLatch start = new CountDownLatch(1);

        Thread chat = new Thread(() -> {
            awaitGate(start);
            agentService.send(session.getId(), "mark the sheet");
        });
        Thread improve = new Thread(() -> {
            awaitGate(start);
            String token = jobService.generatePlan(new PlanRequest(session.getId(), "add a summary sheet")).planToken();
            jobService.applyPlan(new ApplyPlanRequest(token,
                    List.of(new PlanStepDto(0, "ADD_SHEET", Map.of("sheetName", "Summary"), "Add a sheet"))));
        });

        chat.start();
        improve.start();
        start.countDown();
        chat.join(30_000);
        improve.join(30_000);

        await(() -> sessions.get(session.getId()).getCurrentRevision() == 2
                && jobs.values().stream().allMatch(job -> job.getStatus() != JobStatus.PROCESSING));

        assertThat(session.getCurrentRevision()).isEqualTo(2);
        assertThat(jobs.values()).allSatisfy(job -> assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED));

        try (FileInputStream in = new FileInputStream(sessionService.currentPath(session).toFile());
             XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            assertThat(workbook.getSheet(JOB_SHEET)).as("the improve job's edit").isNotNull();
            assertThat(workbook.getSheet("Sales").getRow(0).getCell(CHAT_COLUMN).getStringCellValue())
                    .as("the chat turn's edit").isEqualTo(CHAT_MARK);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** The chat's mutating tool: it edits the in-memory workbook the turn opened. */
    private ToolInvocation chatEdit(XSSFWorkbook workbook) throws InterruptedException {
        Thread.sleep(200);
        workbook.getSheet("Sales").getRow(0).createCell(CHAT_COLUMN).setCellValue(CHAT_MARK);
        return ToolInvocation.ok("ADD_FORMULA", true, "Marked C1", "applied", "applied");
    }

    /** The job's pipeline: it reads the revision it was given and writes the next one itself. */
    private List<ActionResult> jobEdit(String inputPath, String outputPath, JobRecord job) throws Exception {
        Thread.sleep(200);
        try (FileInputStream in = new FileInputStream(inputPath);
             XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            workbook.createSheet(JOB_SHEET);
            try (FileOutputStream out = new FileOutputStream(outputPath)) {
                workbook.write(out);
            }
        }
        return List.of(ActionResult.success(job, "ADD_SHEET", 0, "Added a sheet"));
    }

    private DocumentSession openSession() throws Exception {
        return sessions.get(sessionService.create(upload()).sessionId());
    }

    private AutomationRequest planWithAddSheet() {
        ActionStep step = new ActionStep();
        step.setType("ADD_SHEET");
        step.getProperties().put("sheetName", "Summary");
        AutomationRequest plan = new AutomationRequest();
        plan.setActions(List.of(step));
        return plan;
    }

    private MockMultipartFile upload() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet("Sales");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Product");
            header.createCell(1).setCellValue("Revenue");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("Widget A");
            row.createCell(1).setCellValue(1240);
            workbook.write(out);
            return new MockMultipartFile("file", "sales.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }

    private void awaitGate(CountDownLatch start) {
        try {
            start.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void await(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Timed out waiting for both writers to finish");
    }
}
