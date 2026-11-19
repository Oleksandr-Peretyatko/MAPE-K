package org.anonymous.msrefactor.quizzes.commands.tournamentSatelliteSatellite;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

public class AddParticipantCommand extends Command {
    private Integer tournamentAggregateId;
    private UserDto userDto;

    public AddParticipantCommand(UnitOfWork unitOfWork, String serviceName, Integer tournamentAggregateId, UserDto userDto) {
        super(unitOfWork, serviceName, tournamentAggregateId);
        this.tournamentAggregateId = tournamentAggregateId;
        this.userDto = userDto;
    }

    public Integer getTournamentAggregateId() { return tournamentAggregateId; }
    public UserDto getUserDto() { return userDto; }
}
