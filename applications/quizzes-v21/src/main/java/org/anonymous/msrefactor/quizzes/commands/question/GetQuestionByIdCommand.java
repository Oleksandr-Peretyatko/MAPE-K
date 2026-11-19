package org.anonymous.msrefactor.quizzes.commands.question;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class GetQuestionByIdCommand extends Command {
    private Integer aggregateId;

    public GetQuestionByIdCommand(UnitOfWork unitOfWork, String serviceName, Integer aggregateId) {
        super(unitOfWork, serviceName, aggregateId);
        this.aggregateId = aggregateId;
    }

    public Integer getAggregateId() {
        return aggregateId;
    }
}
