package org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.command;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.SagaAggregate.SagaState;

public class AbortSagaCommand extends Command {
    private Integer aggregateId;
    private SagaState previousState;

    public AbortSagaCommand(Integer aggregateId, String serviceName, SagaState previousState) {
        super(null, serviceName, aggregateId);
        this.aggregateId = aggregateId;
        this.previousState = previousState;
    }

    public Integer getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(Integer aggregateId) {
        this.aggregateId = aggregateId;
    }

    public SagaState getPreviousState() {
        return previousState;
    }

    public void setPreviousState(SagaState previousState) {
        this.previousState = previousState;
    }
}
