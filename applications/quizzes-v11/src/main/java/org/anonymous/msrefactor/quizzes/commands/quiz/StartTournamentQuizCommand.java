package org.anonymous.msrefactor.quizzes.commands.quiz;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class StartTournamentQuizCommand extends Command {
    private Integer userAggregateId;
    private Integer quizAggregateId;

    public StartTournamentQuizCommand(UnitOfWork unitOfWork, String serviceName, Integer userAggregateId, Integer quizAggregateId) {
        super(unitOfWork, serviceName, quizAggregateId);
        this.userAggregateId = userAggregateId;
        this.quizAggregateId = quizAggregateId;
    }

    public Integer getUserAggregateId() { return userAggregateId; }
    public Integer getQuizAggregateId() { return quizAggregateId; }
}
