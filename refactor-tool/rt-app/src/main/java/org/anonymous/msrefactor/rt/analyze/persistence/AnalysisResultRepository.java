package org.anonymous.msrefactor.rt.analyze.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AnalysisResultRepository extends JpaRepository<AnalysisResultEntity, String> {

    Optional<AnalysisResultEntity> findTopByOrderByAnalyzedAtDesc();
}
