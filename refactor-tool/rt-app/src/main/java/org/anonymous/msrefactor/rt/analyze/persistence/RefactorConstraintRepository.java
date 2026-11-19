package org.anonymous.msrefactor.rt.analyze.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RefactorConstraintRepository extends JpaRepository<RefactorConstraintEntity, String> {

    /** Rules scoped to one application. Global rules (null {@code projectId}) are not returned. */
    List<RefactorConstraintEntity> findAllByProjectId(String projectId);

    /** Rules that apply to every application. */
    List<RefactorConstraintEntity> findAllByProjectIdIsNull();
}
