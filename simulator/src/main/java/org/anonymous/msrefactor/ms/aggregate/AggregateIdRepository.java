package org.anonymous.msrefactor.ms.aggregate;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AggregateIdRepository extends JpaRepository<AggregateIdGenerator, Integer> {
}
