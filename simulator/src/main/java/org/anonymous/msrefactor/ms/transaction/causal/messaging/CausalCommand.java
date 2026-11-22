package org.anonymous.msrefactor.ms.transaction.causal.messaging;

import org.anonymous.msrefactor.ms.messaging.Command;

public class CausalCommand extends Command {

    private Command payload;

    protected CausalCommand() {}

    public CausalCommand(Command payload) {
        super(payload.getUnitOfWork(), payload.getServiceName(), payload.getRootAggregateId());
        this.payload = payload;
    }

    public Command getPayload() {
        return payload;
    }
}
