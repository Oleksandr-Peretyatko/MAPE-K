package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.sagas;

import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.GenericSagaState;
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.SagaAggregate;
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.SagaStateConverter;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatellite;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

@Entity
public class SagaTournamentSatellite extends TournamentSatellite implements SagaAggregate {
    @Convert(converter = SagaStateConverter.class)
    private SagaState sagaState;

    public SagaTournamentSatellite() {
        super();
        this.sagaState = GenericSagaState.NOT_IN_SAGA;
    }

    public SagaTournamentSatellite(Integer aggregateId, Integer tournamentAggregateId, TournamentDto tournamentDto,
            UserDto creatorDto, CourseExecutionDto courseExecutionDto) {
        super(aggregateId, tournamentAggregateId, tournamentDto, creatorDto, courseExecutionDto);
        this.sagaState = GenericSagaState.NOT_IN_SAGA;
    }

    /* used to update the satellite by creating new versions */
    public SagaTournamentSatellite(SagaTournamentSatellite other) {
        super(other);
        this.sagaState = other.getSagaState();
    }

    @Override
    public void setSagaState(SagaState state) {
        this.sagaState = state;
    }

    @Override
    public SagaState getSagaState() {
        return this.sagaState;
    }
}
