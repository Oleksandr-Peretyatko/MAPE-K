package org.anonymous.msrefactor.quizzes.commands.tournamentSatelliteSatellite;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class RemoveUserCommand extends Command {
    private Integer satelliteAggregateId;
    private Integer courseExecutionAggregateId;
    private Integer userAggregateId;
    private Long eventVersion;

    public RemoveUserCommand(UnitOfWork unitOfWork, String serviceName, Integer satelliteAggregateId,
            Integer courseExecutionAggregateId, Integer userAggregateId, Long eventVersion) {
        super(unitOfWork, serviceName, satelliteAggregateId);
        this.satelliteAggregateId = satelliteAggregateId;
        this.courseExecutionAggregateId = courseExecutionAggregateId;
        this.userAggregateId = userAggregateId;
        this.eventVersion = eventVersion;
    }

    public Integer getSatelliteAggregateId() {
        return satelliteAggregateId;
    }

    public Integer getCourseExecutionAggregateId() {
        return courseExecutionAggregateId;
    }

    public Integer getUserAggregateId() {
        return userAggregateId;
    }

    public Long getEventVersion() {
        return eventVersion;
    }
}
