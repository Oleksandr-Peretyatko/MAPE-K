package org.anonymous.msrefactor.ms.transaction.causal.unitOfWork.command;

import org.anonymous.msrefactor.ms.messaging.Command;

public class AbortCausalCommand extends Command {
    public AbortCausalCommand(Integer aggregateId, String serviceName) {
        super(null, serviceName, aggregateId);
    }
}
