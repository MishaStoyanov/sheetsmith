package com.ap0stole.sheetsmith.domain.entity;

import com.ap0stole.sheetsmith.domain.enums.JobStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "job_records")
@Getter
@Setter
@NoArgsConstructor
public class JobRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime processingStartedAt;
    private LocalDateTime processingFinishedAt;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String instruction;

    @Column(nullable = false)
    private String inputFilename;

    @Column(nullable = false)
    private String inputFilePath;

    private String resultFilePath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * What the run cost, kept apart because providers price reading and writing differently.
     * Filled from the planning call and from the repair attempt, if there was one. Null means the
     * provider reported nothing — common with a local model — and never that the run was free.
     */
    private Long promptTokens;

    private Long completionTokens;

    private Long totalTokens;

    /** Who asked for the run; null for every run made before there was anyone to attribute it to. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User startedBy;

    @OneToMany(mappedBy = "job", cascade = CascadeType.REMOVE, orphanRemoval = true)
    @OrderBy("executionOrder ASC")
    private List<ActionResult> actions = new ArrayList<>();

    public static JobRecord create(String instruction, String inputFilename, String inputFilePath) {
        JobRecord job = new JobRecord();
        job.createdAt = LocalDateTime.now();
        job.instruction = instruction;
        job.inputFilename = inputFilename;
        job.inputFilePath = inputFilePath;
        job.status = JobStatus.PROCESSING;
        return job;
    }
}
