package org.anonymous.msrefactor.rt.analyze.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OperationQaScoreRepository extends JpaRepository<OperationQaScoreEntity, String> {
    List<OperationQaScoreEntity> findAllByProjectIdIsNull();
    List<OperationQaScoreEntity> findAllByProjectId(String projectId);
    Optional<OperationQaScoreEntity> findByProjectIdIsNullAndOperationAndQaId(String operation, String qaId);
    void deleteAllByProjectId(String projectId);
}
