package org.anonymous.msrefactor.quizzes.commands.tournamentSatelliteSatellite;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto;

public class LeaveTournamentCommand extends Command {
    private Integer tournamentAggregateId;
    private Integer userAggregateId;
    private TournamentDto tournamentDto;

    public LeaveTournamentCommand(UnitOfWork unitOfWork, String serviceName, Integer tournamentAggregateId,
            Integer userAggregateId, TournamentDto tournamentDto) {
        super(unitOfWork, serviceName, tournamentAggregateId);
        this.tournamentAggregateId = tournamentAggregateId;
        this.userAggregateId = userAggregateId;
        this.tournamentDto = tournamentDto;
    }

    public Integer getTournamentAggregateId() {
        return tournamentAggregateId;
    }

    public Integer getUserAggregateId() {
        return userAggregateId;
    }

    public TournamentDto getTournamentDto() {
        return tournamentDto;
    }
}
