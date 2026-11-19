package org.anonymous.msrefactor.quizzes.commands.execution;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class GetCourseExecutionsByUserIdCommand extends Command {
    private Integer userAggregateId;

    public GetCourseExecutionsByUserIdCommand(UnitOfWork unitOfWork, String serviceName, Integer userAggregateId) {
        super(unitOfWork, serviceName, userAggregateId);
        this.userAggregateId = userAggregateId;
    }

    public Integer getUserAggregateId() {
        return userAggregateId;
    }
}
