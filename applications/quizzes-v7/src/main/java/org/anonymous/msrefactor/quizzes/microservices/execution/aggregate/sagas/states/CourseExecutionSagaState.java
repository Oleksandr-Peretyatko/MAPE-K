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
    // Constants moved from the dissolved TournamentSagaState (merge execution <- tournament).
    // Tournament's lock states are checked (forbidden lists + ForSaga queries), so they are kept
    // under the same names and now lock/check the owning Execution root.
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
    IN_ADD_PARTICIPANT {
        @Override
        public String getStateName() {
            return "IN_ADD_PARTICIPANT";
        }
    },
    READ_TOURNAMENT {
        @Override
        public String getStateName() {
            return "READ_TOURNAMENT";
        }
    },
    READ_UPDATED_TOPICS {
        @Override
        public String getStateName() {
            return "READ_UPDATED_TOPICS";
        }
    }
}
