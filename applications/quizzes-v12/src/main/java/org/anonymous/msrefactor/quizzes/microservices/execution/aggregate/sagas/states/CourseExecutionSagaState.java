package org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.sagas.states;

import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.SagaAggregate.SagaState;

public enum CourseExecutionSagaState implements SagaState {
    IN_UPDATE_NAME {
        @Override
        public String getStateName() {
            return "IN_UPDATE_NAME";
        }
    },
    READ_COURSE {
        @Override
        public String getStateName() {
            return "READ_COURSE";
        }
    },
    READ_CREATOR {
        @Override
        public String getStateName() {
            return "READ_CREATOR";
        }
    },
    READ_STUDENT {
        @Override
        public String getStateName() {
            return "READ_STUDENT";
        }
    },
    // moved from the dissolved tournament saga-state enum (merge execution <- tournament) — its lock
    // states are checked, so its locks are retargeted at the owning Execution root
    IN_UPDATE_TOURNAMENT {
        @Override
        public String getStateName() {
            return "IN_UPDATE_TOURNAMENT";
        }
    },
    IN_DELETE_TOURNAMENT {
        @Override
        public String getStateName() {
            return "IN_DELETE_TOURNAMENT";
        }
    },
    READ_TOURNAMENT {
        @Override
        public String getStateName() {
            return "READ_TOURNAMENT";
        }
    }
}
