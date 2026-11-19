package org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.sagas.states;

import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.SagaAggregate.SagaState;

public enum TopicSagaState implements SagaState {
    READ_TOPIC {
        @Override
        public String getStateName() {
            return "READ_TOPIC";
        }
    }
}
