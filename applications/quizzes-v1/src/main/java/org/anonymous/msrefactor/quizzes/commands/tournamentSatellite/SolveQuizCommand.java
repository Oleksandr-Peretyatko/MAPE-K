package org.anonymous.msrefactor.quizzes.commands.tournamentSatellite;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class SolveQuizCommand extends Command {
    private Integer tournamentAggregateId;
    private Integer userAggregateId;
    private Integer answerAggregateId;

    public SolveQuizCommand(UnitOfWork unitOfWork, String serviceName, Integer tournamentAggregateId, Integer userAggregateId, Integer answerAggregateId) {
        super(unitOfWork, serviceName, tournamentAggregateId);
        this.tournamentAggregateId = tournamentAggregateId;
        this.userAggregateId = userAggregateId;
        this.answerAggregateId = answerAggregateId;
    }

    public Integer getTournamentAggregateId() { return tournamentAggregateId; }
    public Integer getUserAggregateId() { return userAggregateId; }
    public Integer getAnswerAggregateId() { return answerAggregateId; }
}
