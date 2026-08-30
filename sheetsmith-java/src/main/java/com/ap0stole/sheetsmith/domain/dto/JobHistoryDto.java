package com.ap0stole.sheetsmith.domain.dto;

import com.ap0stole.sheetsmith.domain.entity.JobRecord;
import com.ap0stole.sheetsmith.domain.enums.JobStatus;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
public class JobHistoryDto {

    private final Long id;
    private final LocalDateTime createdAt;
    private final JobStatus status;
    private final String instruction;
    private final String inputFilename;
    private final List<AppliedActionDto> appliedActions; // empty in list view, populated in detail view
    private final String errorMessage;

    /** Null rather than zero when the provider reported no usage — see {@link JobRecord}. */
    private final Long promptTokens;
    private final Long completionTokens;
    private final Long totalTokens;

    /** Mode, vendor and model; null for runs made before the columns existed. */
    private final String providerMode;
    private final String provider;
    private final String model;

    /** Who asked for it, or null — shown as a dash rather than as an invented name. */
    private final Long startedByUserId;
    private final String startedByName;

    /**
     * When the work actually ran, which is not when the record was created: a job waits for a slot.
     * The screen subtracts them rather than being handed a duration, so it can also say "still
     * going" when the second is missing.
     */
    private final LocalDateTime processingStartedAt;
    private final LocalDateTime processingFinishedAt;

    /**
     * Everything but two fields comes off the run itself.
     * <p>
     * The instruction is passed in because the list view truncates it and the detail view does not,
     * and the actions because only one of the two views may touch that lazy collection at all —
     * which was the reason the constructor grew to eight parameters, seven of which were the run
     * being taken apart at the call site and put back together here.
     */
    private JobHistoryDto(JobRecord job, String instruction, List<AppliedActionDto> appliedActions) {
        this.id = job.getId();
        this.createdAt = job.getCreatedAt();
        this.status = job.getStatus();
        this.instruction = instruction;
        this.inputFilename = job.getInputFilename();
        this.appliedActions = appliedActions;
        this.errorMessage = job.getErrorMessage();
        this.promptTokens = job.getPromptTokens();
        this.completionTokens = job.getCompletionTokens();
        this.totalTokens = job.getTotalTokens();
        this.providerMode = job.getProviderMode();
        this.provider = job.getProvider();
        this.model = job.getModel();
        this.startedByUserId = job.getStartedBy() == null ? null : job.getStartedBy().getId();
        this.startedByName = job.getStartedBy() == null ? null : job.getStartedBy().getName();
        this.processingStartedAt = job.getProcessingStartedAt();
        this.processingFinishedAt = job.getProcessingFinishedAt();
    }

    // For paginated list — does NOT access lazy actions collection
    public static JobHistoryDto from(JobRecord job) {
        return new JobHistoryDto(job, truncate(job.getInstruction()), List.of());
    }

    // For single job detail — accesses actions (requires open session)
    public static JobHistoryDto fromDetail(JobRecord job) {
        List<AppliedActionDto> actions = job.getActions().stream()
                .map(AppliedActionDto::from)
                .toList();
        return new JobHistoryDto(job, truncate(job.getInstruction()), actions);
    }

    private static String truncate(String s) {
        return s.length() > 100 ? s.substring(0, 100) : s;
    }
}
