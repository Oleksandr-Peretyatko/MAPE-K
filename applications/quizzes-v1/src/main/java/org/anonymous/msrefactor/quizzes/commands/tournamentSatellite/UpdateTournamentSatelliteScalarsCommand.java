package org.anonymous.msrefactor.quizzes.commands.tournamentSatellite;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class UpdateTournamentSatelliteScalarsCommand extends Command {
    private Integer tournamentAggregateId;
    private String startTime;
    private Boolean cancelled;

    public UpdateTournamentSatelliteScalarsCommand(UnitOfWork unitOfWork, String serviceName, Integer tournamentAggregateId,
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
