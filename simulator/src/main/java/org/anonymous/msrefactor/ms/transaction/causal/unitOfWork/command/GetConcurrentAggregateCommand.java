package org.anonymous.msrefactor.ms.transaction.causal.unitOfWork.command;

import org.anonymous.msrefactor.ms.messaging.Command;

public class GetConcurrentAggregateCommand extends Command {
    private Long version;

    public GetConcurrentAggregateCommand(Integer aggregateId, String serviceName, Long version) {
        super(null, serviceName, aggregateId);
        this.version = version;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
