package org.anonymous.msrefactor.rt.monitor.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProjectConfigRepository extends JpaRepository<ProjectConfigEntity, Integer> {}
