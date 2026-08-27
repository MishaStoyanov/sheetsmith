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
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.llm.AiPlanningService;
import com.ap0stole.sheetsmith.llm.LlmEngine;
import com.ap0stole.sheetsmith.llm.PlanningResult;
import com.ap0stole.sheetsmith.llm.TokenUsage;
import com.ap0stole.sheetsmith.repository.ActionResultRepository;
import com.ap0stole.sheetsmith.repository.ChatMessageRepository;
import com.ap0stole.sheetsmith.repository.DocumentSessionRepository;
import com.ap0stole.sheetsmith.repository.ChatStepRepository;
import com.ap0stole.sheetsmith.repository.JobRepository;
import com.ap0stole.sheetsmith.requests.ActionStep;
import com.ap0stole.sheetsmith.requests.AutomationRequest;
import com.ap0stole.sheetsmith.services.excel.ActionRegistry;
import com.ap0stole.sheetsmith.services.excel.ExcelAutomationService;
import com.ap0stole.sheetsmith.services.excel.StepTense;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The improve flow now lives inside a session's revision chain, so what is under test here is the
 * lifecycle rather than the Excel work: which revision a job reads, which one it writes, and what
 * survives when the job record is deleted. The POI work itself is stubbed.
 */
class JobServiceSessionTest {

    private static final String JOB_SHEET = "JobEdit";

    private final Map<Long, JobRecord> jobs = new ConcurrentHashMap<>();
    private final Map<String, DocumentSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong jobIds = new AtomicLong();

    /** Flipped on by the one test that wants the first apply to fail and the repair path to run. */
    private final AtomicBoolean failFirstAttempt = new AtomicBoolean(false);

    private Path uploadDir;
    private Path resultDir;
    private DocumentSessionService sessionService;
    private JobRepository jobRepository;
    private JobService jobService;
    private AiPlanningService planningService;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        uploadDir = tempDir.resolve("uploads");
        resultDir = tempDir.resolve("results");

        FileStorageConfig storageConfig = new FileStorageConfig();
        storageConfig.setUploadDir(uploadDir.toString());
        storageConfig.setResultDir(resultDir.toString());
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

        jobRepository = mock(JobRepository.class);
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

        planningService = mock(AiPlanningService.class);
        when(planningService.generatePlan(anyString(), anyString())).thenReturn(planWith("ADD_SHEET"));

        ActionRegistry actionRegistry = mock(ActionRegistry.class);
        when(actionRegistry.describe(anyString(), any(), any(StepTense.class))).thenReturn("Add a sheet");

        ExcelAutomationService automationService = mock(ExcelAutomationService.class);
        when(automationService.applyChanges(anyString(), anyString(), any(), any()))
                .thenAnswer(call -> failFirstAttempt.getAndSet(false)
                        ? List.of(ActionResult.failure(call.getArgument(3), "ADD_SHEET", 0, "sheet already exists"))
                        : applySheet(call.getArgument(0), call.getArgument(1), call.getArgument(3)));

        sessionService = new DocumentSessionService(storageConfig, sessionRepository, messageRepository,
                mock(ChatStepRepository.class), new SessionSchemaCache(new SchemaExtractorService(new ChatConfig())));

        jobService = new JobService(jobRepository, actionResultRepository, new FileStorageService(storageConfig),
                new SchemaExtractorService(new ChatConfig()), planningService, automationService, actionRegistry,
                mock(PathGuard.class), new Semaphore(1), sessionService, new SessionLockRegistry());
    }

    @Test
    @DisplayName("an improve job lands as the session's next revision, leaving the previous one on disk")
    void improveRunBecomesTheNextRevision() throws Exception {
        DocumentSession session = openSession();

        Long jobId = runImprove(session, "add a summary sheet");

        assertThat(session.getCurrentRevision()).isEqualTo(1);
        assertThat(sessionService.revisionPath(session, 0)).exists();
        assertThat(sessionService.revisionPath(session, 1)).exists();
        assertThat(sheetNames(sessionService.revisionPath(session, 0))).doesNotContain(JOB_SHEET);
        assertThat(sheetNames(sessionService.revisionPath(session, 1))).contains(JOB_SHEET);

        JobRecord job = jobs.get(jobId);
        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.getInputFilePath()).isEqualTo(sessionService.revisionPath(session, 0).toString());
        assertThat(job.getResultFilePath()).isEqualTo(sessionService.revisionPath(session, 1).toString());
    }

    @Test
    @DisplayName("undo after an improve run brings the pre-run sheet back")
    void revertUndoesAnImproveRun() throws Exception {
        DocumentSession session = openSession();
        runImprove(session, "add a summary sheet");

        int revision = sessionService.revert(session.getId(), 0);

        assertThat(revision).isEqualTo(2);
        assertThat(sheetNames(sessionService.currentPath(session))).doesNotContain(JOB_SHEET);
        assertThat(sessionService.revisionPath(session, 1)).exists();
    }

    @Test
    @DisplayName("deleting a job whose files are session revisions keeps the session's chain intact")
    void deletingASessionJobKeepsTheRevisions() throws Exception {
        DocumentSession session = openSession();
        Long jobId = runImprove(session, "add a summary sheet");

        jobService.deleteJob(jobId);

        assertThat(sessionService.revisionPath(session, 0)).exists();
        assertThat(sessionService.revisionPath(session, 1)).exists();
        assertThat(sessionService.currentPath(session)).exists();
    }

    @Test
    @DisplayName("a plan with no steps is refused out loud rather than rendered as an empty screen")
    void emptyPlanFailsLoudly() throws Exception {
        DocumentSession session = openSession();
        // What a model does when the catalog has nothing for the instruction: it answers in prose,
        // which parses down to zero actions.
        when(planningService.generatePlan(anyString(), anyString()))
                .thenReturn(new PlanningResult(new AutomationRequest(), TokenUsage.NONE, LlmEngine.UNKNOWN));

        assertThatThrownBy(() -> jobService.generatePlan(new PlanRequest(session.getId(), "make it nicer")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("did not propose any changes");
    }


    @Test
    @DisplayName("steps that name no real action are refused, not shown as \"Unknown step\" cards")
    void aPlanOfUnknownStepsFailsLoudly() throws Exception {
        DocumentSession session = openSession();
        // Found by running the app against a 0.5B model: it invented its own JSON shape
        // ({"stepName": …, "stepArgs": …}) and every step parsed with a null type. That answered
        // 200 with review cards reading "Unknown step" — a plan nobody can act on or diagnose.
        when(planningService.generatePlan(anyString(), anyString())).thenReturn(planWith(null));

        assertThatThrownBy(() -> jobService.generatePlan(new PlanRequest(session.getId(), "tidy it")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("could not read");
    }

    @Test
    @DisplayName("one bad step among good ones still gives a plan — the card can be dropped")
    void aPartlyUnknownPlanSurvives() throws Exception {
        DocumentSession session = openSession();
        ActionStep good = new ActionStep();
        good.setType("ADD_SHEET");
        good.getProperties().put("name", "Summary");
        ActionStep bad = new ActionStep();
        bad.getProperties().put("stepName", "Do something clever");
        AutomationRequest plan = new AutomationRequest();
        plan.setActions(List.of(good, bad));
        when(planningService.generatePlan(anyString(), anyString()))
                .thenReturn(new PlanningResult(plan, TokenUsage.NONE, LlmEngine.UNKNOWN));

        assertThat(jobService.generatePlan(new PlanRequest(session.getId(), "tidy it")).steps())
                .hasSize(2);
    }

    @Test
    @DisplayName("what the planning call cost survives the wait for the user to approve the plan")
    void planningCostReachesTheJobRecord() throws Exception {
        DocumentSession session = openSession();
        // The awkward part of the audit: the spend happens in generatePlan, and the record it
        // belongs to is only created once the user presses Apply.
        when(planningService.generatePlan(anyString(), anyString()))
                .thenReturn(planWith("ADD_SHEET", new TokenUsage(1200L, 300L, 1500L)));

        Long jobId = runImprove(session, "add a summary sheet");

        JobRecord job = jobs.get(jobId);
        assertThat(job.getPromptTokens()).isEqualTo(1200L);
        assertThat(job.getCompletionTokens()).isEqualTo(300L);
        assertThat(job.getTotalTokens()).isEqualTo(1500L);
    }

    @Test
    @DisplayName("a repair attempt adds its own spend to the plan's, rather than replacing it")
    void repairCostIsAddedToTheRun() throws Exception {
        DocumentSession session = openSession();
        when(planningService.generatePlan(anyString(), anyString()))
                .thenReturn(planWith("ADD_SHEET", new TokenUsage(1000L, 200L, 1200L)));
        when(planningService.fixPlan(anyString(), anyString(), anyString()))
                .thenReturn(planWith("ADD_SHEET", new TokenUsage(400L, 100L, 500L)));
        failFirstAttempt.set(true);

        Long jobId = runImprove(session, "add a summary sheet");

        // The second call happens during apply, long after the plan was priced — a run that
        // reported only the cheaper half would understate every repaired run in the audit.
        JobRecord job = jobs.get(jobId);
        assertThat(job.getPromptTokens()).isEqualTo(1400L);
        assertThat(job.getCompletionTokens()).isEqualTo(300L);
        assertThat(job.getTotalTokens()).isEqualTo(1700L);
    }

    @Test
    @DisplayName("the run records which engine answered, not which one is configured now")
    void engineReachesTheJobRecord() throws Exception {
        DocumentSession session = openSession();
        when(planningService.generatePlan(anyString(), anyString()))
                .thenReturn(planWith("ADD_SHEET", TokenUsage.NONE,
                        new LlmEngine("CLOUD", "GEMINI", "gemini-3.7-flash")));

        Long jobId = runImprove(session, "add a summary sheet");

        JobRecord job = jobs.get(jobId);
        assertThat(job.getProviderMode()).isEqualTo("CLOUD");
        assertThat(job.getProvider()).isEqualTo("GEMINI");
        assertThat(job.getModel()).isEqualTo("gemini-3.7-flash");
    }

    @Test
    @DisplayName("a repair on a different model leaves the run attributed to the one that planned it")
    void theModelThatPlannedTheRunKeepsTheAttribution() throws Exception {
        DocumentSession session = openSession();
        // Settings are editable between /plan and /apply, so the two calls of one run can genuinely
        // land on different models. One column cannot hold two answers; the planning call — the one
        // that did the thinking and spent most of the tokens — is the one it holds.
        when(planningService.generatePlan(anyString(), anyString()))
                .thenReturn(planWith("ADD_SHEET", TokenUsage.NONE, new LlmEngine("CLOUD", "OPENAI", "gpt-4o")));
        when(planningService.fixPlan(anyString(), anyString(), anyString()))
                .thenReturn(planWith("ADD_SHEET", TokenUsage.NONE,
                        new LlmEngine("LOCAL", "OLLAMA", "gemma4:12b")));
        failFirstAttempt.set(true);

        Long jobId = runImprove(session, "add a summary sheet");

        JobRecord job = jobs.get(jobId);
        assertThat(job.getProviderMode()).isEqualTo("CLOUD");
        assertThat(job.getProvider()).isEqualTo("OPENAI");
        assertThat(job.getModel()).isEqualTo("gpt-4o");
    }

    @Test
    @DisplayName("a provider that reports nothing leaves the columns empty, not zeroed")
    void silentProviderLeavesTheColumnsNull() throws Exception {
        DocumentSession session = openSession();
        when(planningService.generatePlan(anyString(), anyString()))
                .thenReturn(planWith("ADD_SHEET", TokenUsage.NONE));

        Long jobId = runImprove(session, "add a summary sheet");

        JobRecord job = jobs.get(jobId);
        assertThat(job.getPromptTokens()).isNull();
        assertThat(job.getCompletionTokens()).isNull();
        assertThat(job.getTotalTokens()).isNull();
    }

    @Test
    @DisplayName("deleting a job outside any session still takes its files with it")
    void deletingAFilePassingJobStillRemovesItsFiles() throws Exception {
        Files.createDirectories(uploadDir);
        Files.createDirectories(resultDir);
        Path input = Files.createFile(uploadDir.resolve("in.xlsx"));
        Path result = Files.createFile(resultDir.resolve("out.xlsx"));

        JobRecord job = JobRecord.create("bold the header", "in.xlsx", input.toString());
        job.setResultFilePath(result.toString());
        jobRepository.save(job);

        jobService.deleteJob(job.getId());

        assertThat(input).doesNotExist();
        assertThat(result).doesNotExist();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Drives the real plan-then-apply contract and waits for the virtual thread to publish. */
    private Long runImprove(DocumentSession session, String instruction) {
        String token = jobService.generatePlan(new PlanRequest(session.getId(), instruction)).planToken();
        Long jobId = jobService.applyPlan(new ApplyPlanRequest(token,
                List.of(new PlanStepDto(0, "ADD_SHEET", Map.of("sheetName", "Summary"), "Add a sheet"))));

        await(() -> {
            JobRecord job = jobs.get(jobId);
            return job != null && job.getStatus() != JobStatus.PROCESSING
                    && sessions.get(session.getId()).getCurrentRevision() > 0;
        });
        return jobId;
    }

    private DocumentSession openSession() throws Exception {
        String sessionId = sessionService.create(upload()).sessionId();
        return sessions.get(sessionId);
    }

    /** Stands in for the POI pipeline: reads the input revision, adds a marker sheet, writes the output. */
    private List<ActionResult> applySheet(String inputPath, String outputPath, JobRecord job) throws Exception {
        try (FileInputStream in = new FileInputStream(inputPath);
             XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            workbook.createSheet(JOB_SHEET);
            try (FileOutputStream out = new FileOutputStream(outputPath)) {
                workbook.write(out);
            }
        }
        return List.of(ActionResult.success(job, "ADD_SHEET", 0, "Added a sheet"));
    }

    private List<String> sheetNames(Path workbookPath) throws Exception {
        try (FileInputStream in = new FileInputStream(workbookPath.toFile());
             XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            return java.util.stream.IntStream.range(0, workbook.getNumberOfSheets())
                    .mapToObj(i -> workbook.getSheetAt(i).getSheetName())
                    .toList();
        }
    }

    private PlanningResult planWith(String type) {
        return planWith(type, TokenUsage.NONE, LlmEngine.UNKNOWN);
    }

    private PlanningResult planWith(String type, TokenUsage usage) {
        return planWith(type, usage, LlmEngine.UNKNOWN);
    }

    private PlanningResult planWith(String type, TokenUsage usage, LlmEngine engine) {
        ActionStep step = new ActionStep();
        step.setType(type);
        step.getProperties().put("sheetName", "Summary");
        AutomationRequest plan = new AutomationRequest();
        plan.setActions(List.of(step));
        return new PlanningResult(plan, usage, engine);
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
        throw new AssertionError("Timed out waiting for the improve job to finish");
    }
}
