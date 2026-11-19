package org.anonymous.msrefactor.quizzes.commands.tournamentSatellite;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class DeactivateTournamentSatelliteCommand extends Command {
    private Integer tournamentAggregateId;

    public DeactivateTournamentSatelliteCommand(UnitOfWork unitOfWork, String serviceName, Integer tournamentAggregateId) {
        super(unitOfWork, serviceName, tournamentAggregateId);
        this.tournamentAggregateId = tournamentAggregateId;
    }

    public Integer getTournamentAggregateId() {
        return tournamentAggregateId;
    }
}
