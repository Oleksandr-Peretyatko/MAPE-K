package org.anonymous.msrefactor.quizzes.commands.tournamentSatelliteSatellite;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class FindTournamentSatelliteSatelliteByTournamentIdCommand extends Command {
    private Integer tournamentAggregateId;

    public FindTournamentSatelliteSatelliteByTournamentIdCommand(UnitOfWork unitOfWork, String serviceName, Integer tournamentAggregateId) {
        super(unitOfWork, serviceName, tournamentAggregateId);
        this.tournamentAggregateId = tournamentAggregateId;
    }

    public Integer getTournamentAggregateId() { return tournamentAggregateId; }
}
