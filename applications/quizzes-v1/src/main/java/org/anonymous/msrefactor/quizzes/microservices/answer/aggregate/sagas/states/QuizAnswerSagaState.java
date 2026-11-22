package org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.sagas.states;

import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.SagaAggregate.SagaState;

public enum QuizAnswerSagaState implements SagaState {
    READ_QUIZ_ANSWER {
        @Override
        public String getStateName() {
            return "READ_QUIZ_ANSWER";
        }
    },
    STARTED_QUIZ {
        @Override
        public String getStateName() {
            return "STARTED_QUIZ";
        }
    }
}
