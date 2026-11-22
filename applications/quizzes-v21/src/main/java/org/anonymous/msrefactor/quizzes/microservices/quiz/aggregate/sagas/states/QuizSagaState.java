package org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.sagas.states;

import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.SagaAggregate.SagaState;

public enum QuizSagaState implements SagaState {
    CREATED_QUIZ {
        @Override
        public String getStateName() {
            return "CREATED_QUIZ";
        }
    },
    STARTED_TOURNAMENT_QUIZ {
        @Override
        public String getStateName() {
            return "STARTED_TOURNAMENT_QUIZ";
        }
    },
    READ_QUIZ {
        @Override
        public String getStateName() {
            return "READ_QUIZ";
        }
    }
}
