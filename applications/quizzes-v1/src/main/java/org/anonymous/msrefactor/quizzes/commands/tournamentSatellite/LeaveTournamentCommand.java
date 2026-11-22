package org.anonymous.msrefactor.quizzes.commands.tournamentSatellite;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class LeaveTournamentCommand extends Command {
    private Integer tournamentAggregateId;
    private Integer userAggregateId;

    public LeaveTournamentCommand(UnitOfWork unitOfWork, String serviceName, Integer tournamentAggregateId, Integer userAggregateId) {
        super(unitOfWork, serviceName, tournamentAggregateId);
        this.tournamentAggregateId = tournamentAggregateId;
        this.userAggregateId = userAggregateId;
    }

    public Integer getTournamentAggregateId() { return tournamentAggregateId; }
    public Integer getUserAggregateId() { return userAggregateId; }
}
