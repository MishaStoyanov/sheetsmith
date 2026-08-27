package com.ap0stole.sheetsmith.repository;

import com.ap0stole.sheetsmith.domain.entity.JobRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;

public interface JobRepository extends JpaRepository<JobRecord, Long>, JpaSpecificationExecutor<JobRecord> {

    List<JobRecord> findByCreatedAtBefore(LocalDateTime threshold);

    /**
     * Both listings fetch the owner with the row.
     * <p>
     * {@code startedBy} is a lazy relation, and the history DTO reads the owner's name — outside a
     * transaction that is a proxy nobody can initialise. It went unnoticed at first because a run
     * with no owner has no proxy to fail on, so the bug was invisible until a run actually belonged
     * to somebody. A fetch join rather than an open transaction, so a page of twenty is one query
     * rather than twenty-one.
     */
    @Override
    @EntityGraph(attributePaths = "startedBy")
    Page<JobRecord> findAll(Pageable pageable);

    @Override
    @EntityGraph(attributePaths = "startedBy")
    Page<JobRecord> findAll(Specification<JobRecord> spec, Pageable pageable);
}
