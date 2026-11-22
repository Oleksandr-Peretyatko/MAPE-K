package org.anonymous.msrefactor.quizzes.commands.tournamentSatelliteSatellite;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto;

public class CreateTournamentSatelliteSatelliteCommand extends Command {
    private Integer tournamentAggregateId;
    private Integer tournamentSatelliteAggregateId;
    private TournamentDto tournamentDto;
    private CourseExecutionDto courseExecutionDto;

    public CreateTournamentSatelliteSatelliteCommand(UnitOfWork unitOfWork, String serviceName,
            Integer tournamentAggregateId, Integer tournamentSatelliteAggregateId, TournamentDto tournamentDto,
            CourseExecutionDto courseExecutionDto) {
        super(unitOfWork, serviceName, tournamentAggregateId);
        this.tournamentAggregateId = tournamentAggregateId;
        this.tournamentSatelliteAggregateId = tournamentSatelliteAggregateId;
        this.tournamentDto = tournamentDto;
        this.courseExecutionDto = courseExecutionDto;
    }

    public Integer getTournamentAggregateId() {
        return tournamentAggregateId;
    }

    public Integer getTournamentSatelliteAggregateId() {
        return tournamentSatelliteAggregateId;
    }

    public TournamentDto getTournamentDto() {
        return tournamentDto;
    }

    public CourseExecutionDto getCourseExecutionDto() {
        return courseExecutionDto;
    }
}
