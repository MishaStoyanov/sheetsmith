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
