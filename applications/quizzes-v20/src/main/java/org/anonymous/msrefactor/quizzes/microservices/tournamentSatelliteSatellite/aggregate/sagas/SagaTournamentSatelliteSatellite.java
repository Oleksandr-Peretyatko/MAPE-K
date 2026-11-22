package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.sagas;

import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.GenericSagaState;
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.SagaAggregate;
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.SagaStateConverter;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.TournamentSatelliteSatellite;

@Entity
public class SagaTournamentSatelliteSatellite extends TournamentSatelliteSatellite implements SagaAggregate {
    @Convert(converter = SagaStateConverter.class)
    private SagaState sagaState;

    public SagaTournamentSatelliteSatellite() {
        super();
        this.sagaState = GenericSagaState.NOT_IN_SAGA;
    }

    public SagaTournamentSatelliteSatellite(Integer aggregateId, Integer tournamentAggregateId,
            Integer tournamentSatelliteAggregateId, TournamentDto tournamentDto,
            CourseExecutionDto courseExecutionDto) {
        super(aggregateId, tournamentAggregateId, tournamentSatelliteAggregateId, tournamentDto, courseExecutionDto);
        this.sagaState = GenericSagaState.NOT_IN_SAGA;
    }

    /* used to update the satellite by creating new versions */
    public SagaTournamentSatelliteSatellite(SagaTournamentSatelliteSatellite other) {
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
