package org.anonymous.msrefactor.quizzes.commands.tournamentSatellite;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

public class CreateTournamentSatelliteCommand extends Command {
    private Integer tournamentAggregateId;
    private TournamentDto tournamentDto;
    private UserDto creatorDto;
    private CourseExecutionDto courseExecutionDto;

    public CreateTournamentSatelliteCommand(UnitOfWork unitOfWork, String serviceName, Integer tournamentAggregateId,
            TournamentDto tournamentDto, UserDto creatorDto, CourseExecutionDto courseExecutionDto) {
        super(unitOfWork, serviceName, tournamentAggregateId);
        this.tournamentAggregateId = tournamentAggregateId;
        this.tournamentDto = tournamentDto;
        this.creatorDto = creatorDto;
        this.courseExecutionDto = courseExecutionDto;
    }

    public Integer getTournamentAggregateId() {
        return tournamentAggregateId;
    }

    public TournamentDto getTournamentDto() {
        return tournamentDto;
    }

    public UserDto getCreatorDto() {
        return creatorDto;
    }

    public CourseExecutionDto getCourseExecutionDto() {
        return courseExecutionDto;
    }
}
