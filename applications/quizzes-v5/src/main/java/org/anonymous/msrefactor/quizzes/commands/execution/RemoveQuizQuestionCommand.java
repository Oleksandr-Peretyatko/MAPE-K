package org.anonymous.msrefactor.quizzes.commands.execution;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class RemoveQuizQuestionCommand extends Command {
    private final Integer executionAggregateId;
    private final Integer questionAggregateId;

    public RemoveQuizQuestionCommand(UnitOfWork unitOfWork,
            String serviceName,
            Integer executionAggregateId,
            Integer questionAggregateId) {
        super(unitOfWork, serviceName, executionAggregateId);
        this.executionAggregateId = executionAggregateId;
        this.questionAggregateId = questionAggregateId;
    }

    public Integer getExecutionAggregateId() {
        return executionAggregateId;
    }

    public Integer getQuestionAggregateId() {
        return questionAggregateId;
    }
}
