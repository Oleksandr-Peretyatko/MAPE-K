package org.anonymous.msrefactor.rt.analyze.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface QualityAttributeRepository extends JpaRepository<QualityAttributeEntity, String> {
    List<QualityAttributeEntity> findAllByActiveTrueOrderBySortOrderAsc();
    Optional<QualityAttributeEntity> findByName(String name);
    boolean existsByName(String name);
}
