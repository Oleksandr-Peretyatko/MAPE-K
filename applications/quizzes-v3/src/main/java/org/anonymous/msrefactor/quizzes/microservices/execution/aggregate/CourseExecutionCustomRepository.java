package org.anonymous.msrefactor.quizzes.microservices.execution.aggregate;

import java.util.Optional;
import java.util.Set;

public interface CourseExecutionCustomRepository {
    Set<Integer> findCourseExecutionIdsOfAllNonDeleted();

    // Member-id -> root-id lookup for the absorbed Quiz entities (merge execution <- quiz).
    Optional<Integer> findExecutionIdByQuizId(Integer quizId);
}
