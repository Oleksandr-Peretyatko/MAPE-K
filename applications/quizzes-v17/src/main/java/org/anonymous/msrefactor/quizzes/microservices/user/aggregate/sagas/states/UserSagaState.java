package org.anonymous.msrefactor.quizzes.microservices.user.aggregate.sagas.states;

import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.SagaAggregate.SagaState;

public enum UserSagaState implements SagaState {
    READ_USER {
        @Override
        public String getStateName() {
            return "READ_USER";
        }
    },
    READ_STUDENT {
        @Override
        public String getStateName() {
            return "READ_STUDENT";
        }
    },
}
