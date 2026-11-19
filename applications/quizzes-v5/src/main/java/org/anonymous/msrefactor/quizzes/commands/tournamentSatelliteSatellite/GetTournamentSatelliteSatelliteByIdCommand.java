package org.anonymous.msrefactor.quizzes.commands.tournamentSatelliteSatellite;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class GetTournamentSatelliteSatelliteByIdCommand extends Command {
    private Integer satelliteAggregateId;

    public GetTournamentSatelliteSatelliteByIdCommand(UnitOfWork unitOfWork, String serviceName, Integer satelliteAggregateId) {
        super(unitOfWork, serviceName, satelliteAggregateId);
        this.satelliteAggregateId = satelliteAggregateId;
    }

    public Integer getSatelliteAggregateId() { return satelliteAggregateId; }
}
