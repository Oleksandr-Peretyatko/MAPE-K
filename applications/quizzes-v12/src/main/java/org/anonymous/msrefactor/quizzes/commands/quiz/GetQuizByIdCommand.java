package org.anonymous.msrefactor.quizzes.commands.quiz;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class GetQuizByIdCommand extends Command {
    private Integer aggregateId;

    public GetQuizByIdCommand(UnitOfWork unitOfWork, String serviceName, Integer aggregateId) {
        super(unitOfWork, serviceName, aggregateId);
        this.aggregateId = aggregateId;
    }

    public Integer getAggregateId() {
        return aggregateId;
    }
}
