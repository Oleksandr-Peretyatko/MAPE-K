package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.sagas.factories;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.TournamentSatelliteSatellite;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.TournamentSatelliteSatelliteDto;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.TournamentSatelliteSatelliteFactory;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.sagas.SagaTournamentSatelliteSatellite;

@Service
@Profile("sagas")
public class SagasTournamentSatelliteSatelliteFactory implements TournamentSatelliteSatelliteFactory {

    @Override
    public TournamentSatelliteSatellite createTournamentSatelliteSatellite(Integer aggregateId,
            Integer tournamentAggregateId, Integer tournamentSatelliteAggregateId, TournamentDto tournamentDto,
            CourseExecutionDto courseExecutionDto) {
        return new SagaTournamentSatelliteSatellite(aggregateId, tournamentAggregateId, tournamentSatelliteAggregateId,
                tournamentDto, courseExecutionDto);
    }

    @Override
    public TournamentSatelliteSatellite createTournamentSatelliteSatelliteFromExisting(
            TournamentSatelliteSatellite existingSatellite) {
        return new SagaTournamentSatelliteSatellite((SagaTournamentSatelliteSatellite) existingSatellite);
    }

    @Override
    public TournamentSatelliteSatelliteDto createTournamentSatelliteSatelliteDto(
            TournamentSatelliteSatellite tournamentSatelliteSatellite) {
        return tournamentSatelliteSatellite.buildDto();
    }
}
