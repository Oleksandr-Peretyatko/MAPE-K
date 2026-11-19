package org.anonymous.msrefactor.quizzes.commands.execution;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class GetOpenedTournamentsForCourseExecutionCommand extends Command {
    private final Integer executionAggregateId;

    public GetOpenedTournamentsForCourseExecutionCommand(UnitOfWork unitOfWork, String serviceName,
            Integer executionAggregateId) {
        super(unitOfWork, serviceName, executionAggregateId);
        this.executionAggregateId = executionAggregateId;
    }

    public Integer getExecutionAggregateId() {
        return executionAggregateId;
    }
}
