package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.domain.dto.*;
import com.ap0stole.sheetsmith.domain.entity.ActionResult;
import com.ap0stole.sheetsmith.domain.entity.DocumentSession;
import com.ap0stole.sheetsmith.domain.entity.JobRecord;
import com.ap0stole.sheetsmith.domain.enums.JobStatus;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import com.ap0stole.sheetsmith.llm.AiPlanningService;
import com.ap0stole.sheetsmith.llm.LlmEngine;
import com.ap0stole.sheetsmith.llm.PlanningResult;
import com.ap0stole.sheetsmith.llm.TokenUsage;
import com.ap0stole.sheetsmith.repository.ActionResultRepository;
import com.ap0stole.sheetsmith.repository.JobRepository;
import com.ap0stole.sheetsmith.requests.ActionStep;
import com.ap0stole.sheetsmith.requests.AutomationRequest;
import com.ap0stole.sheetsmith.services.excel.ActionRegistry;
import com.ap0stole.sheetsmith.services.excel.ExcelAutomationService;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Runs improve jobs. The plan-then-apply flow works on a {@link DocumentSession}: it reads that
 * session's current revision and commits its result as the next one, so an improve run sits in the
 * same append-only chain as a chat turn and can be undone the same way. The scripting entry points
 * ({@code /improve}, {@code /improve/path}) still pass files around and own no session.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

    private final JobRepository jobRepository;
    private final ActionResultRepository actionResultRepository;
    private final FileStorageService fileStorageService;
    private final SchemaExtractorService schemaExtractorService;
    private final AiPlanningService aiPlanningService;
    private final ExcelAutomationService excelAutomationService;
    private final ActionRegistry actionRegistry;
    private final PathGuard pathGuard;
    private final Semaphore jobSemaphore;
    private final DocumentSessionService documentSessionService;
    private final SessionLockRegistry sessionLocks;

    private final ConcurrentHashMap<String, PendingPlan> pendingPlans = new ConcurrentHashMap<>();

    /**
     * Carries {@code usage} because the planning call is paid for here, minutes before the user
     * approves the plan and a {@link JobRecord} exists to record it against.
     */
    private record PendingPlan(String sessionId, String filename, String instruction,
                               TokenUsage usage, LlmEngine engine) {}

    // ── Plan-then-apply flow (session-backed) ─────────────────────────────────

    /**
     * Plans against the session's current revision. No lock: revisions are immutable once written,
     * so reading one while another writer appends the next is safe — and this call waits on the LLM.
     */
    public PlanResponseDto generatePlan(PlanRequest request) {
        DocumentSession session = documentSessionService.require(request.sessionId());

        ExcelSchemaDto schema = documentSessionService.schema(session);
        PlanningResult planned = aiPlanningService.generatePlan(request.instruction(), schema.toPromptString());
        AutomationRequest plan = planned.plan();

        // A model that answers in prose instead of JSON parses down to zero steps, and a plan of
        // zero steps renders as an empty screen that explains nothing — indistinguishable from a
        // broken app. Say it out loud instead.
        if (plan.getActions() == null || plan.getActions().isEmpty()) {
            log.warn("Planner returned no steps for instruction: {}", request.instruction());
            throw new ApiException(ErrorCode.LLM_FAILURE,
                    "The AI did not propose any changes for that instruction — it may not be something "
                            + "this app can do to a sheet, or the wording may need to be more specific. "
                            + "Try naming the column and what should happen to it.");
        }

        // The same failure wearing a different face: a small model answers with JSON of its own
        // invention — {"stepName": "...", "stepArgs": {...}} — which parses into steps carrying no
        // type at all. Those render as review cards reading "Unknown step", a plan the user can
        // neither act on nor diagnose.
        //
        // A type that is merely unrecognised is deliberately NOT caught here: the apply stage
        // reports an unknown action per step, which is the more useful place to find out. Only a
        // step with no type is unreadable on its face. And only refuse when nothing is usable — a
        // plan that is mostly right with one bad card is still worth reviewing.
        long usable = plan.getActions().stream()
                .filter(action -> action.getType() != null && !action.getType().isBlank())
                .count();
        if (usable == 0) {
            log.warn("Planner returned {} step(s), none of a known type, for instruction: {}",
                    plan.getActions().size(), request.instruction());
            throw new ApiException(ErrorCode.LLM_FAILURE,
                    "The AI answered in a shape this app could not read — none of the steps it "
                            + "proposed name an action that exists. This usually means the model is "
                            + "too small to follow the format; try a larger one, or rephrase the "
                            + "instruction more simply.");
        }

        String token = UUID.randomUUID().toString();
        pendingPlans.put(token, new PendingPlan(session.getId(), session.getOriginalFilename(),
                request.instruction(), planned.usage(), planned.engine()));

        List<PlanStepDto> steps = new ArrayList<>();
        List<ActionStep> actions = plan.getActions();
        for (int i = 0; i < actions.size(); i++) {
            ActionStep a = actions.get(i);
            steps.add(new PlanStepDto(i, a.getType(), a.getProperties(),
                    actionRegistry.describe(a.getType(), a.getProperties(), StepTense.IMPERATIVE)));
        }

        log.info("Plan generated: {} steps, token={}", steps.size(), token);
        return new PlanResponseDto(token, steps);
    }

    /**
     * Re-narrates steps the user edited in the review cards, so the description always matches the
     * properties that will actually be applied.
     */
    public List<PlanStepDto> describeSteps(List<PlanStepDto> steps) {
        return steps.stream()
                .map(step -> new PlanStepDto(step.index(), step.type(), step.properties(),
                        actionRegistry.describe(step.type(), step.properties(), StepTense.IMPERATIVE)))
                .toList();
    }

    /**
     * The result becomes the session's next revision, so the job's input and result files are two
     * links of the session's chain — job history and its download keep working, but the sheet has
     * only one home.
     */
    public Long applyPlan(ApplyPlanRequest request) {
        PendingPlan pending = pendingPlans.remove(request.planToken());
        if (pending == null) {
            throw new ApiException(ErrorCode.JOB_NOT_FOUND, "Plan token not found or expired");
        }

        DocumentSession session = documentSessionService.require(pending.sessionId());

        List<ActionStep> steps = request.steps().stream().map(dto -> {
            ActionStep step = new ActionStep();
            step.setType(dto.type());
            step.getProperties().putAll(dto.properties());
            return step;
        }).collect(Collectors.toList());

        AutomationRequest filteredPlan = new AutomationRequest();
        filteredPlan.setActions(steps);

        // A best guess only: the revisions the job actually reads and writes are resolved under the
        // session lock, since a chat turn may commit before the job gets its slot.
        JobRecord job = JobRecord.create(pending.instruction(), pending.filename(),
                documentSessionService.currentPath(session).toString());
        addUsage(job, pending.usage());
        recordEngine(job, pending.engine());
        jobRepository.save(job);

        Long jobId = job.getId();
        Thread.ofVirtual().start(() ->
                processSessionJob(jobId, pending.sessionId(), pending.instruction(), filteredPlan));

        log.info("Job {} submitted via applyPlan ({} steps) for session {}", jobId, steps.size(), pending.sessionId());
        return jobId;
    }

    // ── Scripting entry points: file-passing, no session ──────────────────────

    public Long createAndSubmit(MultipartFile file, String instruction) throws IOException {
        validateFile(file);
        String inputPath = fileStorageService.saveInput(file);
        String resultPath = fileStorageService.buildResultPath(inputPath);

        JobRecord job = JobRecord.create(instruction, file.getOriginalFilename(), inputPath);
        jobRepository.save(job);

        Thread.ofVirtual().start(() ->
                processJobInternally(job.getId(), inputPath, resultPath, instruction, null));

        log.info("Job {} submitted for file '{}'", job.getId(), file.getOriginalFilename());
        return job.getId();
    }

    /** Both paths come from the caller, so nothing here runs before {@link PathGuard} has confined them. */
    public Long createAndSubmitByPath(ImproveByPathRequest request) {
        String inputPath = pathGuard.resolveInput(request.getInputPath(), "inputPath").toString();
        String outputPath = pathGuard.resolveOutput(request.getOutputPath(), "outputPath").toString();
        String filename = Path.of(inputPath).getFileName().toString();

        JobRecord job = JobRecord.create(request.getInstruction(), filename, inputPath);
        jobRepository.save(job);

        Thread.ofVirtual().start(() ->
                processJobInternally(job.getId(), inputPath, outputPath, request.getInstruction(), null));

        log.info("Job {} submitted for path '{}'", job.getId(), inputPath);
        return job.getId();
    }

    // ── History / download / delete ──────────────────────────────────────────

    public Page<JobHistoryDto> getHistory(Pageable pageable) {
        return jobRepository.findAll(pageable).map(JobHistoryDto::from);
    }

    @Transactional(readOnly = true)
    public JobHistoryDto getById(Long id) {
        return jobRepository.findById(id)
                .map(JobHistoryDto::fromDetail)
                .orElseThrow(() -> new ApiException(ErrorCode.JOB_NOT_FOUND, "Job not found: " + id));
    }

    public Resource downloadResult(Long id) {
        JobRecord job = jobRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.JOB_NOT_FOUND, "Job not found: " + id));

        if (job.getResultFilePath() == null) {
            throw new ApiException(ErrorCode.FILE_NOT_FOUND, "No result file for job " + id);
        }

        Path resultPath = Path.of(job.getResultFilePath());
        if (!Files.exists(resultPath)) {
            throw new ApiException(ErrorCode.FILE_NOT_FOUND, "Result file not found for job " + id);
        }

        return new FileSystemResource(resultPath);
    }

    /**
     * Files a live session owns are left alone — a session-backed job's input and result are two
     * revisions of that session's chain, and deleting them would break undo or take the current
     * sheet with them. Such a job drops its record only.
     */
    @Transactional
    public void deleteJob(Long id) {
        JobRecord job = jobRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.JOB_NOT_FOUND, "Job not found: " + id));

        fileStorageService.deleteJobFiles(job.getInputFilePath(), job.getResultFilePath());

        jobRepository.delete(job);
        log.info("Deleted job {}", id);
    }

    // ── Core async execution ──────────────────────────────────────────────────

    /**
     * The file-passing flow, for the scripting endpoints: input and result are standalone files and
     * no session is involved.
     */
    private void processJobInternally(Long jobId, String inputPath, String resultPath,
                                      String instruction, AutomationRequest prePlan) {
        if (!acquireSlot(jobId)) return;

        try {
            JobRecord job = startJob(jobId, inputPath);
            List<ActionResult> results = runPlan(job, inputPath, resultPath, instruction, prePlan);
            finalizeJob(job, results, resultPath);
        } catch (Exception e) {
            log.error("Job {} failed with exception", jobId, e);
            failJob(jobId, e.getMessage());
        } finally {
            jobSemaphore.release();
        }
    }

    /**
     * The session-backed flow. Reading the current revision, applying the plan and publishing the
     * next one is one read-modify-write, so the whole thing runs under the session lock — otherwise
     * a chat turn could commit in between and one of the two edits would be lost.
     * <p>
     * The lock is taken <em>after</em> the semaphore, never before: see {@link SessionLockRegistry}.
     */
    private void processSessionJob(Long jobId, String sessionId, String instruction, AutomationRequest prePlan) {
        if (!acquireSlot(jobId)) return;

        ReentrantLock lock = sessionLocks.acquire(sessionId);
        try {
            DocumentSession session = documentSessionService.require(sessionId);
            String inputPath = documentSessionService.currentPath(session).toString();
            String resultPath = documentSessionService.nextRevisionPath(session).toString();

            JobRecord job = startJob(jobId, inputPath);
            List<ActionResult> results = runPlan(job, inputPath, resultPath, instruction, prePlan);
            JobStatus status = finalizeJob(job, results, resultPath);

            // A job that changed nothing must not spend a revision — undo would step over a no-op.
            if (status != JobStatus.FAILED) {
                documentSessionService.commitExternalRevision(session,
                        "Applied outside the chat: \"" + instruction + "\" — I'm now working with the new version.");
            }
        } catch (Exception e) {
            log.error("Job {} failed with exception", jobId, e);
            failJob(jobId, e.getMessage());
        } finally {
            lock.unlock();
            jobSemaphore.release();
        }
    }

    /** @return false when the wait was interrupted, in which case the job is already marked failed */
    private boolean acquireSlot(Long jobId) {
        try {
            jobSemaphore.acquire();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failJob(jobId, "Processing interrupted");
            return false;
        }
    }

    private JobRecord startJob(Long jobId, String inputPath) {
        JobRecord job = jobRepository.findById(jobId).orElseThrow();
        job.setInputFilePath(inputPath);
        job.setProcessingStartedAt(LocalDateTime.now());
        return jobRepository.save(job);
    }

    /**
     * prePlan == null  →  generate plan from LLM first
     * prePlan != null  →  use the provided (user-filtered) plan directly
     */
    private List<ActionResult> runPlan(JobRecord job, String inputPath, String resultPath,
                                       String instruction, AutomationRequest prePlan) {
        ExcelSchemaDto schema = schemaExtractorService.extract(inputPath);
        AutomationRequest plan;
        if (prePlan != null) {
            plan = prePlan;
        } else {
            PlanningResult planned = aiPlanningService.generatePlan(instruction, schema.toPromptString());
            plan = planned.plan();
            recordEngine(job, planned.engine());
            recordUsage(job, planned.usage());
        }

        List<ActionResult> results = excelAutomationService.applyChanges(inputPath, resultPath, plan, job);

        if (shouldRetry(results, plan)) {
            log.info("Job {} triggering retry via fixPlan", job.getId());
            PlanningResult repaired = aiPlanningService.fixPlan(
                    instruction, buildErrorSummary(results), schema.toPromptString());
            recordEngine(job, repaired.engine());
            recordUsage(job, repaired.usage());
            results = excelAutomationService.applyChanges(inputPath, resultPath, repaired.plan(), job);
        }

        return results;
    }

    /**
     * Written the moment it is known rather than at the end: a run that spent tokens and then threw
     * takes the failure path, which reloads the record — and an audit that forgets what a failed
     * run cost is exactly the audit nobody needs.
     */
    private void recordUsage(JobRecord job, TokenUsage usage) {
        if (usage == null || usage.isEmpty()) {
            return;
        }
        addUsage(job, usage);
        jobRepository.save(job);
    }

    /**
     * First engine wins: a run is attributed to the model that planned it, which is the call that
     * did the thinking and spent most of the tokens. Settings can change between planning and
     * apply, so a repair may genuinely run on another model — that is said out loud rather than
     * silently overwriting the attribution, since one column cannot hold two answers.
     */
    private void recordEngine(JobRecord job, LlmEngine engine) {
        if (engine == null || !engine.isKnown()) {
            return;
        }
        if (job.getProviderMode() == null && job.getProvider() == null && job.getModel() == null) {
            job.setProviderMode(engine.providerMode());
            job.setProvider(engine.provider());
            job.setModel(engine.model());
            return;
        }
        if (!Objects.equals(job.getModel(), engine.model())) {
            log.warn("Job {} was planned on {}/{} but a later call ran on {}/{}; the run stays "
                            + "attributed to the model that planned it",
                    job.getId(), job.getProviderMode(), job.getModel(),
                    engine.providerMode(), engine.model());
        }
    }

    /** Adds one call's cost to whatever the record already carries; null stays null. */
    private static void addUsage(JobRecord job, TokenUsage usage) {
        if (usage == null || usage.isEmpty()) {
            return;
        }
        TokenUsage total = new TokenUsage(job.getPromptTokens(), job.getCompletionTokens(), job.getTotalTokens())
                .plus(usage);
        job.setPromptTokens(total.promptTokens());
        job.setCompletionTokens(total.completionTokens());
        job.setTotalTokens(total.totalTokens());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean shouldRetry(List<ActionResult> results, AutomationRequest plan) {
        if (plan.getActions() == null || plan.getActions().isEmpty()) return true;
        long successCount = results.stream().filter(ActionResult::isSuccess).count();
        return successCount == 0 && !results.isEmpty();
    }

    private JobStatus finalizeJob(JobRecord job, List<ActionResult> results, String resultPath) {
        long successCount = results.stream().filter(ActionResult::isSuccess).count();
        long totalCount = results.size();

        JobStatus status;
        if (totalCount == 0 || successCount == 0) {
            status = JobStatus.FAILED;
        } else if (successCount == totalCount) {
            status = JobStatus.COMPLETED;
        } else {
            status = JobStatus.PARTIAL;
        }

        job.setStatus(status);
        job.setResultFilePath(resultPath);
        job.setProcessingFinishedAt(LocalDateTime.now());
        jobRepository.save(job);
        actionResultRepository.saveAll(results);

        log.info("Job {} finished with status {} ({}/{} actions succeeded)",
                job.getId(), status, successCount, totalCount);
        return status;
    }

    private void failJob(Long jobId, String reason) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(JobStatus.FAILED);
            job.setErrorMessage(reason);
            job.setProcessingFinishedAt(LocalDateTime.now());
            jobRepository.save(job);
        });
    }

    private String buildErrorSummary(List<ActionResult> results) {
        return results.stream()
                .filter(r -> !r.isSuccess())
                .map(r -> r.getActionType() + ": " + r.getErrorMessage())
                .collect(Collectors.joining("\n"));
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.FILE_INVALID, "File is empty");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".xlsx")) {
            throw new ApiException(ErrorCode.FILE_INVALID, "Only .xlsx files are supported", "file");
        }
    }
}
