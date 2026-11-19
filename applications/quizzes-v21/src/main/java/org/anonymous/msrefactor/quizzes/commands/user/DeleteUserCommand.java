package org.anonymous.msrefactor.quizzes.commands.user;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class DeleteUserCommand extends Command {
    private Integer userAggregateId;

    public DeleteUserCommand(UnitOfWork unitOfWork, String serviceName, Integer userAggregateId) {
        super(unitOfWork, serviceName, userAggregateId);
        this.userAggregateId = userAggregateId;
    }

    public Integer getUserAggregateId() {
        return userAggregateId;
    }
}
