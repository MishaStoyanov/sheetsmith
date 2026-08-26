package com.ap0stole.sheetsmith.repository;

import com.ap0stole.sheetsmith.domain.entity.DocumentSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface DocumentSessionRepository extends JpaRepository<DocumentSession, String> {

    List<DocumentSession> findByLastActivityAtBefore(LocalDateTime cutoff);
}
