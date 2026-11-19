package org.anonymous.msrefactor.quizzes.microservices.execution.aggregate;

import java.util.Optional;
import java.util.Set;

public interface CourseExecutionCustomRepository {
    Set<Integer> findCourseExecutionIdsOfAllNonDeleted();
    Optional<Integer> findExecutionIdByQuizId(Integer quizId);
    Optional<Integer> findExecutionIdByTournamentId(Integer tournamentId);
}
