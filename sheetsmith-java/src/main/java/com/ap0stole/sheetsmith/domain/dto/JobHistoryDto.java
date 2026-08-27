package com.ap0stole.sheetsmith.domain.dto;

import com.ap0stole.sheetsmith.domain.entity.JobRecord;
import com.ap0stole.sheetsmith.domain.enums.JobStatus;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

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

    private JobHistoryDto(Long id, LocalDateTime createdAt, JobStatus status,
                          String instruction, String inputFilename, List<AppliedActionDto> appliedActions,
                          String errorMessage, JobRecord job) {
        this.id = id;
        this.createdAt = createdAt;
        this.status = status;
        this.instruction = instruction;
        this.inputFilename = inputFilename;
        this.appliedActions = appliedActions;
        this.errorMessage = errorMessage;
        this.promptTokens = job.getPromptTokens();
        this.completionTokens = job.getCompletionTokens();
        this.totalTokens = job.getTotalTokens();
    }

    // For paginated list — does NOT access lazy actions collection
    public static JobHistoryDto from(JobRecord job) {
        return new JobHistoryDto(job.getId(), job.getCreatedAt(), job.getStatus(),
                truncate(job.getInstruction()), job.getInputFilename(), List.of(), job.getErrorMessage(), job);
    }

    // For single job detail — accesses actions (requires open session)
    public static JobHistoryDto fromDetail(JobRecord job) {
        List<AppliedActionDto> actions = job.getActions().stream()
                .map(AppliedActionDto::from)
                .collect(Collectors.toList());
        return new JobHistoryDto(job.getId(), job.getCreatedAt(), job.getStatus(),
                truncate(job.getInstruction()), job.getInputFilename(), actions, job.getErrorMessage(), job);
    }

    private static String truncate(String s) {
        return s.length() > 100 ? s.substring(0, 100) : s;
    }
}
