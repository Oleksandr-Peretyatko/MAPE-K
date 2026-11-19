package org.anonymous.msrefactor.quizzes.commands.tournament;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class InvalidateQuizCommand extends Command {
    private final Integer tournamentAggregateId;
    private final Integer aggregateId;
    private final Long aggregateVersion;

    public InvalidateQuizCommand(UnitOfWork unitOfWork, String serviceName, Integer tournamentAggregateId,
            Integer aggregateId, Long aggregateVersion) {
        super(unitOfWork, serviceName, tournamentAggregateId);
        this.tournamentAggregateId = tournamentAggregateId;
        this.aggregateId = aggregateId;
        this.aggregateVersion = aggregateVersion;
    }

    public Integer getTournamentAggregateId() {
        return tournamentAggregateId;
    }

    public Integer getAggregateId() {
        return aggregateId;
    }

    public Long getAggregateVersion() {
        return aggregateVersion;
    }
}
