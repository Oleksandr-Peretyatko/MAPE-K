package org.anonymous.msrefactor.rt.plan.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlanDocumentRepository extends JpaRepository<PlanDocumentEntity, String> {

    List<PlanDocumentEntity> findAllByOrderByCreatedAtDesc();
}
