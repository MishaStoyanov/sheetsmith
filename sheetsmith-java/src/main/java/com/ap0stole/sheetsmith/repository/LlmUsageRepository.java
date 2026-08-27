package com.ap0stole.sheetsmith.repository;

import com.ap0stole.sheetsmith.domain.entity.LlmUsage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmUsageRepository extends JpaRepository<LlmUsage, Long> {
}
