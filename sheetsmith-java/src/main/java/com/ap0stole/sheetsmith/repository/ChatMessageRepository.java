package com.ap0stole.sheetsmith.repository;

import com.ap0stole.sheetsmith.domain.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findBySessionIdOrderByIdAsc(String sessionId);

    void deleteBySessionId(String sessionId);
}
