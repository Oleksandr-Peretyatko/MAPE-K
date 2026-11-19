package org.anonymous.msrefactor.quizzes.commands.execution;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class RemoveQuizCommand extends Command {
    private final Integer quizAggregateId;

    public RemoveQuizCommand(UnitOfWork unitOfWork, String serviceName, Integer quizAggregateId) {
        super(unitOfWork, serviceName, quizAggregateId);
        this.quizAggregateId = quizAggregateId;
    }

    public Integer getQuizAggregateId() {
        return quizAggregateId;
    }
}
