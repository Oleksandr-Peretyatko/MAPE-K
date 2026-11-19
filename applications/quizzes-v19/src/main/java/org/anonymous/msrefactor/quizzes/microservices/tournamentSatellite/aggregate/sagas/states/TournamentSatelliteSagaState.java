package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.sagas.states;

import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.SagaAggregate.SagaState;

public enum TournamentSatelliteSagaState implements SagaState {
    READ_SATELLITE {
        @Override
        public String getStateName() {
            return "READ_SATELLITE";
        }
    },
    IN_UPDATE_SATELLITE {
        @Override
        public String getStateName() {
            return "IN_UPDATE_SATELLITE";
        }
    },
    IN_DELETE_SATELLITE {
        @Override
        public String getStateName() {
            return "IN_DELETE_SATELLITE";
        }
    }
}
