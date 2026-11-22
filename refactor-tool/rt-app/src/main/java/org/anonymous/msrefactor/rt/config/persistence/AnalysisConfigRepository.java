package org.anonymous.msrefactor.rt.config.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisConfigRepository extends JpaRepository<AnalysisConfigEntity, Integer> {
}
