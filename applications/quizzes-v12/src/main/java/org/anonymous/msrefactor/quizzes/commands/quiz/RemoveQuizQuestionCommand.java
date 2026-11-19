package org.anonymous.msrefactor.quizzes.commands.quiz;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class RemoveQuizQuestionCommand extends Command {
    private final Integer quizAggregateId;
    private final Integer questionAggregateId;

    public RemoveQuizQuestionCommand(UnitOfWork unitOfWork,
            String serviceName,
            Integer quizAggregateId,
            Integer questionAggregateId) {
        super(unitOfWork, serviceName, quizAggregateId);
        this.quizAggregateId = quizAggregateId;
        this.questionAggregateId = questionAggregateId;
    }

    public Integer getQuizAggregateId() {
        return quizAggregateId;
    }

    public Integer getQuestionAggregateId() {
        return questionAggregateId;
    }
}
