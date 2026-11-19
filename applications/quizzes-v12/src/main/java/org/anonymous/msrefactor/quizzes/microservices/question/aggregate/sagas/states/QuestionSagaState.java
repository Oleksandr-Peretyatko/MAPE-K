package org.anonymous.msrefactor.quizzes.microservices.question.aggregate.sagas.states;

import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.SagaAggregate.SagaState;

public enum QuestionSagaState implements SagaState {
    READ_QUESTION {
        @Override
        public String getStateName() {
            return "READ_QUESTION";
        }
    }
}
