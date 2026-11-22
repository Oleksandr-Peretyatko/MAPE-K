package org.anonymous.msrefactor.quizzes.commands.tournamentSatelliteSatellite;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class UpdateTournamentSatelliteSatelliteScalarsCommand extends Command {
    private Integer tournamentAggregateId;
    private String startTime;
    private Boolean cancelled;

    public UpdateTournamentSatelliteSatelliteScalarsCommand(UnitOfWork unitOfWork, String serviceName, Integer tournamentAggregateId,
            String startTime, Boolean cancelled) {
        super(unitOfWork, serviceName, tournamentAggregateId);
        this.tournamentAggregateId = tournamentAggregateId;
        this.startTime = startTime;
        this.cancelled = cancelled;
    }

    public Integer getTournamentAggregateId() { return tournamentAggregateId; }
    public String getStartTime() { return startTime; }
    public Boolean getCancelled() { return cancelled; }
}
