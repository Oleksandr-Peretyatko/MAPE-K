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
    },
    // moved from the dissolved answer aggregate (merge quiz <- answer); the locks land on the Quiz root
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
