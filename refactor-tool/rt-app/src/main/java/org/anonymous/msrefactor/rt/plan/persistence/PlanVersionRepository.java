package org.anonymous.msrefactor.rt.plan.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlanVersionRepository extends JpaRepository<PlanVersionEntity, String> {

    List<PlanVersionEntity> findByPlanIdOrderByVersionNumberAsc(String planId);

    Optional<PlanVersionEntity> findTopByPlanIdOrderByVersionNumberDesc(String planId);

    List<PlanVersionEntity> findByPlanIdAndApprovalState(String planId, String approvalState);

    Optional<PlanVersionEntity> findTopByPlanIdAndApprovalStateOrderByVersionNumberDesc(
            String planId, String approvalState);
}
