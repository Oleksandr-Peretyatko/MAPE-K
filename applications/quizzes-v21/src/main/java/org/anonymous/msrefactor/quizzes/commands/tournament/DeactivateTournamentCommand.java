package org.anonymous.msrefactor.quizzes.commands.tournament;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

/*
 * Sent by the tournamentSatellite workflows when anonymisation/disenrollment of
 * the creator deactivates the tournament — the state propagation the split
 * turned into a cross-aggregate interaction.
 */
public class DeactivateTournamentCommand extends Command {
    private Integer tournamentAggregateId;

    public DeactivateTournamentCommand(UnitOfWork unitOfWork, String serviceName, Integer tournamentAggregateId) {
        super(unitOfWork, serviceName, tournamentAggregateId);
        this.tournamentAggregateId = tournamentAggregateId;
    }

    public Integer getTournamentAggregateId() {
        return tournamentAggregateId;
    }
}
