package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.sagas.states;

import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.SagaAggregate.SagaState;

public enum TournamentSatelliteSagaState implements SagaState {
    IN_UPDATE_TOURNAMENT_SATELLITE {
        @Override
        public String getStateName() {
            return "IN_UPDATE_TOURNAMENT_SATELLITE";
        }
    },
    IN_DELETE_TOURNAMENT_SATELLITE {
        @Override
        public String getStateName() {
            return "IN_DELETE_TOURNAMENT_SATELLITE";
        }
    },
    IN_ADD_PARTICIPANT {
        @Override
        public String getStateName() {
            return "IN_ADD_PARTICIPANT";
        }
    },
    READ_TOURNAMENT_SATELLITE {
        @Override
        public String getStateName() {
            return "READ_TOURNAMENT_SATELLITE";
        }
    }
}
