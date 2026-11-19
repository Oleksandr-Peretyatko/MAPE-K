package org.anonymous.msrefactor.quizzes.commands.answer;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class GetQuizAnswerByQuizIdAndUserIdCommand extends Command {
    private Integer quizAggregateId;
    private Integer userAggregateId;

    public GetQuizAnswerByQuizIdAndUserIdCommand(UnitOfWork unitOfWork, String serviceName, Integer quizAnswerAggregateId, Integer quizAggregateId, Integer userAggregateId) {
        super(unitOfWork, serviceName, quizAnswerAggregateId);
        this.quizAggregateId = quizAggregateId;
        this.userAggregateId = userAggregateId;
    }

    public Integer getQuizAggregateId() { return quizAggregateId; }
    public Integer getUserAggregateId() { return userAggregateId; }
}
