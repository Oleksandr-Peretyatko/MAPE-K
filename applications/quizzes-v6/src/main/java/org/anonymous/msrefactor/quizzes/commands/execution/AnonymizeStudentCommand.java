package org.anonymous.msrefactor.quizzes.commands.execution;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class AnonymizeStudentCommand extends Command {
    private Integer executionAggregateId;
    private Integer userAggregateId;

    public AnonymizeStudentCommand(UnitOfWork unitOfWork, String serviceName, Integer executionAggregateId, Integer userAggregateId) {
        super(unitOfWork, serviceName, executionAggregateId);
        this.executionAggregateId = executionAggregateId;
        this.userAggregateId = userAggregateId;
    }

    public Integer getExecutionAggregateId() {
        return executionAggregateId;
    }

    public Integer getUserAggregateId() {
        return userAggregateId;
    }
}
