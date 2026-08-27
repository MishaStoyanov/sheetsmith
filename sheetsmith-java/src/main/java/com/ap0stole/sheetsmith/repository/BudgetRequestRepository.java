package com.ap0stole.sheetsmith.repository;

import com.ap0stole.sheetsmith.domain.entity.BudgetRequest;
import com.ap0stole.sheetsmith.domain.enums.BudgetRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BudgetRequestRepository extends JpaRepository<BudgetRequest, Long> {

    Optional<BudgetRequest> findFirstByUserIdAndStatus(Long userId, BudgetRequestStatus status);

    /** The decision this person has not been shown yet, newest first in case anything slipped. */
    Optional<BudgetRequest> findFirstByUserIdAndStatusInAndSeenAtIsNullOrderByDecidedAtDesc(
            Long userId, List<BudgetRequestStatus> statuses);

    List<BudgetRequest> findByStatus(BudgetRequestStatus status);
}
