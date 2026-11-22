package org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.sagas.repositories;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.Tournament;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentCustomRepository;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentRepository;

import java.util.Optional;
import java.util.Set;

@Service
@Profile("sagas")
public class TournamentCustomRepositorySagas implements TournamentCustomRepository {

    @Autowired
    private TournamentRepository tournamentRepository;

    @Override
    public Set<Integer> findAllRelevantTournamentIds(Integer executionAggregateId) {
        return tournamentRepository.findAllTournamentIdsOfNotDeletedAndNotInactiveByCourseExecutionForSaga(executionAggregateId);
    }

    @Override
    public Optional<Tournament> findTournamentById(Integer aggregateId) {
        return tournamentRepository.findById(aggregateId);
    }
}