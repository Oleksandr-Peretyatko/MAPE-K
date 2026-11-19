package org.anonymous.msrefactor.quizzes.microservices.course.aggregate.sagas.states;

import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.SagaAggregate.SagaState;

public enum CourseSagaState implements SagaState {
    READ_COURSE {
        @Override
        public String getStateName() {
            return "READ_COURSE";
        }
    }
}
