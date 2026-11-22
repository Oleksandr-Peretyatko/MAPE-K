package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.sagas.factories;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatellite;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatelliteDto;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatelliteFactory;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.sagas.SagaTournamentSatellite;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

@Service
@Profile("sagas")
public class SagasTournamentSatelliteFactory implements TournamentSatelliteFactory {

    @Override
    public TournamentSatellite createTournamentSatellite(Integer aggregateId, Integer tournamentAggregateId,
            TournamentDto tournamentDto, UserDto creatorDto, CourseExecutionDto courseExecutionDto) {
        return new SagaTournamentSatellite(aggregateId, tournamentAggregateId, tournamentDto, creatorDto,
                courseExecutionDto);
    }

    @Override
    public TournamentSatellite createTournamentSatelliteFromExisting(TournamentSatellite existingSatellite) {
        return new SagaTournamentSatellite((SagaTournamentSatellite) existingSatellite);
    }

    @Override
    public TournamentSatelliteDto createTournamentSatelliteDto(TournamentSatellite tournamentSatellite) {
        return tournamentSatellite.buildDto();
    }
}
