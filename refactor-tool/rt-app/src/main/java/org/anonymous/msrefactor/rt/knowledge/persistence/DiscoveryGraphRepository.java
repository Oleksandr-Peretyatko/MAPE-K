package org.anonymous.msrefactor.rt.knowledge.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DiscoveryGraphRepository extends JpaRepository<DiscoveryGraphEntity, String> {

    Optional<DiscoveryGraphEntity> findTopByCodeVersionIdOrderByDiscoveredAtDesc(String codeVersionId);
}
