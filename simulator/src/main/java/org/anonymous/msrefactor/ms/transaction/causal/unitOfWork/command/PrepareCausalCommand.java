package org.anonymous.msrefactor.ms.transaction.causal.unitOfWork.command;

import org.anonymous.msrefactor.ms.aggregate.Aggregate;
import org.anonymous.msrefactor.ms.messaging.Command;

public class PrepareCausalCommand extends Command {
    private Aggregate aggregate;

    public PrepareCausalCommand(Integer aggregateId, String serviceName, Aggregate aggregate) {
        super(null, serviceName, aggregateId);
        this.aggregate = aggregate;
    }

    public Aggregate getAggregate() {
        return aggregate;
    }

    public void setAggregate(Aggregate aggregate) {
        this.aggregate = aggregate;
    }
}
