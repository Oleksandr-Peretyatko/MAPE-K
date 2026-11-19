package org.anonymous.msrefactor.quizzes.commands.tournamentSatelliteSatellite;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class UpdateTournamentSatelliteSatelliteInfoCommand extends Command {
    private Integer tournamentAggregateId;
    private String startTime;
    private boolean cancelled;
    private Long tournamentVersion;

    public UpdateTournamentSatelliteSatelliteInfoCommand(UnitOfWork unitOfWork, String serviceName,
            Integer tournamentAggregateId, String startTime, boolean cancelled, Long tournamentVersion) {
        super(unitOfWork, serviceName, tournamentAggregateId);
        this.tournamentAggregateId = tournamentAggregateId;
        this.startTime = startTime;
        this.cancelled = cancelled;
        this.tournamentVersion = tournamentVersion;
    }

    public Integer getTournamentAggregateId() {
        return tournamentAggregateId;
    }

    public String getStartTime() {
        return startTime;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public Long getTournamentVersion() {
        return tournamentVersion;
    }
}
