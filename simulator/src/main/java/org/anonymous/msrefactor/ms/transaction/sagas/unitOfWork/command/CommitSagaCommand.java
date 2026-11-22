package org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.command;

import org.anonymous.msrefactor.ms.messaging.Command;

public class CommitSagaCommand extends Command {
    private Integer aggregateId;

    public CommitSagaCommand(Integer aggregateId, String serviceName) {
        super(null, serviceName, aggregateId);
        this.aggregateId = aggregateId;
    }

    public Integer getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(Integer aggregateId) {
        this.aggregateId = aggregateId;
    }
}
