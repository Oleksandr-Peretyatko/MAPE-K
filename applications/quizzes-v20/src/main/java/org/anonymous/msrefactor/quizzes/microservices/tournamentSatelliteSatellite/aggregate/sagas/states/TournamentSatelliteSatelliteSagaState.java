package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.sagas.states;

import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.SagaAggregate.SagaState;

public enum TournamentSatelliteSatelliteSagaState implements SagaState {
    READ_SATELLITE_SATELLITE {
        @Override
        public String getStateName() {
            return "READ_SATELLITE_SATELLITE";
        }
    },
    IN_UPDATE_SATELLITE_SATELLITE {
        @Override
        public String getStateName() {
            return "IN_UPDATE_SATELLITE_SATELLITE";
        }
    },
    IN_DELETE_SATELLITE_SATELLITE {
        @Override
        public String getStateName() {
            return "IN_DELETE_SATELLITE_SATELLITE";
        }
    }
}
