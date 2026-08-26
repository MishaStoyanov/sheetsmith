package com.ap0stole.sheetsmith.repository;

import com.ap0stole.sheetsmith.domain.entity.JobRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface JobRepository extends JpaRepository<JobRecord, Long> {

    List<JobRecord> findByCreatedAtBefore(LocalDateTime threshold);
}
