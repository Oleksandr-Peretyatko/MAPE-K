package org.anonymous.msrefactor.quizzes.commands.tournamentSatellite;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class GetTournamentSatelliteByIdCommand extends Command {
    private Integer satelliteAggregateId;

    public GetTournamentSatelliteByIdCommand(UnitOfWork unitOfWork, String serviceName, Integer satelliteAggregateId) {
        super(unitOfWork, serviceName, satelliteAggregateId);
        this.satelliteAggregateId = satelliteAggregateId;
    }

    public Integer getSatelliteAggregateId() { return satelliteAggregateId; }
}
