package com.ap0stole.sheetsmith.repository;

import com.ap0stole.sheetsmith.domain.entity.ChatStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ChatStepRepository extends JpaRepository<ChatStep, Long> {

    List<ChatStep> findByMessageIdInOrderByMessageIdAscExecutionOrderAsc(Collection<Long> messageIds);

    void deleteByMessageIdIn(Collection<Long> messageIds);
}
