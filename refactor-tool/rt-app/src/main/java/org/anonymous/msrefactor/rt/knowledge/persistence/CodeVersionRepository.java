package org.anonymous.msrefactor.rt.knowledge.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CodeVersionRepository extends JpaRepository<CodeVersionEntity, String> {

    Optional<CodeVersionEntity> findByVersionNumber(int versionNumber);


    boolean existsByVersionNumber(int versionNumber);


    List<CodeVersionEntity> findAllByOrderByVersionNumberAsc();

    Optional<CodeVersionEntity> findTopByStatusOrderByVersionNumberDesc(String status);

}
