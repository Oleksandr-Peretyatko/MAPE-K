package org.anonymous.msrefactor.quizzes.commands.tournamentSatellite;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

public class CreateTournamentSatelliteCommand extends Command {
    private Integer tournamentAggregateId;
    private UserDto creatorDto;
    private Integer courseExecutionAggregateId;
    private Long courseExecutionVersion;
    private String startTime;
    private boolean cancelled;

    public CreateTournamentSatelliteCommand(UnitOfWork unitOfWork, String serviceName, Integer tournamentAggregateId,
            UserDto creatorDto, Integer courseExecutionAggregateId, Long courseExecutionVersion, String startTime,
            boolean cancelled) {
        super(unitOfWork, serviceName, null);
        this.tournamentAggregateId = tournamentAggregateId;
        this.creatorDto = creatorDto;
        this.courseExecutionAggregateId = courseExecutionAggregateId;
        this.courseExecutionVersion = courseExecutionVersion;
        this.startTime = startTime;
        this.cancelled = cancelled;
    }

    public Integer getTournamentAggregateId() { return tournamentAggregateId; }
    public UserDto getCreatorDto() { return creatorDto; }
    public Integer getCourseExecutionAggregateId() { return courseExecutionAggregateId; }
    public Long getCourseExecutionVersion() { return courseExecutionVersion; }
    public String getStartTime() { return startTime; }
    public boolean isCancelled() { return cancelled; }
}
