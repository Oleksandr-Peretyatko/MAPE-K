package org.anonymous.msrefactor.ms.transaction.sagas.aggregate;

public interface SagaAggregate {
    void setSagaState(SagaState state);
    SagaState getSagaState();

    public interface SagaState {
        String getStateName();
    }
}