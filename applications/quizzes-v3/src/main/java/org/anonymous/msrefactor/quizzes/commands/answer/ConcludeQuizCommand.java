package org.anonymous.msrefactor.quizzes.commands.answer;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class ConcludeQuizCommand extends Command {
    private Integer quizAggregateId;
    private Integer userAggregateId;

    public ConcludeQuizCommand(UnitOfWork unitOfWork, String serviceName, Integer quizAggregateId, Integer userAggregateId) {
        super(unitOfWork, serviceName, null);
        this.quizAggregateId = quizAggregateId;
        this.userAggregateId = userAggregateId;
    }

    public Integer getQuizAggregateId() { return quizAggregateId; }
    public Integer getUserAggregateId() { return userAggregateId; }
}
