package org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.sagas.repositories;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionCustomRepository;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionRepository;

import java.util.Optional;
import java.util.Set;

@Service
@Profile("sagas")
public class CourseExecutionCustomRepositorySagas implements CourseExecutionCustomRepository {

    @Autowired
    private CourseExecutionRepository courseExecutionRepository;

    @Override
    public Set<Integer> findCourseExecutionIdsOfAllNonDeleted() {
        return courseExecutionRepository.findCourseExecutionIdsOfAllNonDeletedForSaga();
    }

    @Override
    public Optional<Integer> findExecutionIdByTournamentId(Integer tournamentId) {
        return courseExecutionRepository.findExecutionIdsByTournamentId(tournamentId).stream().findFirst();
    }
}