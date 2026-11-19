package org.anonymous.msrefactor.quizzes.commands.execution;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

/*
 * Bare lookup (layer 2.2 part 4, merge execution <- tournament): resolves the owning execution of
 * a tournament member so a saga can retarget a checked semantic lock to the root before it sends.
 * Sent without a SagaCommand wrapper and without a lock.
 */
public class GetExecutionIdByTournamentIdCommand extends Command {
    private Integer tournamentAggregateId;

    public GetExecutionIdByTournamentIdCommand(UnitOfWork unitOfWork, String serviceName,
            Integer tournamentAggregateId) {
        super(unitOfWork, serviceName, tournamentAggregateId);
        this.tournamentAggregateId = tournamentAggregateId;
    }

    public Integer getTournamentAggregateId() {
        return tournamentAggregateId;
    }
}
