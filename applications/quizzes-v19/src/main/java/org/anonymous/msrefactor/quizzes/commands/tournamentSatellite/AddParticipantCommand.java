package org.anonymous.msrefactor.quizzes.commands.tournamentSatellite;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

public class AddParticipantCommand extends Command {
    private Integer tournamentAggregateId;
    private UserDto userDto;
    private TournamentDto tournamentDto;

    public AddParticipantCommand(UnitOfWork unitOfWork, String serviceName, Integer tournamentAggregateId,
            UserDto userDto, TournamentDto tournamentDto) {
        super(unitOfWork, serviceName, tournamentAggregateId);
        this.tournamentAggregateId = tournamentAggregateId;
        this.userDto = userDto;
        this.tournamentDto = tournamentDto;
    }

    public Integer getTournamentAggregateId() {
        return tournamentAggregateId;
    }

    public UserDto getUserDto() {
        return userDto;
    }

    public TournamentDto getTournamentDto() {
        return tournamentDto;
    }
}
