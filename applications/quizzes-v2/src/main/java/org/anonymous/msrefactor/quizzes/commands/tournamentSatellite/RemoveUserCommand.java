package org.anonymous.msrefactor.quizzes.commands.tournamentSatellite;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class RemoveUserCommand extends Command {
    private Integer satelliteAggregateId;
    private Integer executionAggregateId;
    private Integer userAggregateId;
    private Long eventVersion;

    public RemoveUserCommand(UnitOfWork unitOfWork, String serviceName, Integer satelliteAggregateId,
            Integer executionAggregateId, Integer userAggregateId, Long eventVersion) {
        super(unitOfWork, serviceName, satelliteAggregateId);
        this.satelliteAggregateId = satelliteAggregateId;
        this.executionAggregateId = executionAggregateId;
        this.userAggregateId = userAggregateId;
        this.eventVersion = eventVersion;
    }

    public Integer getSatelliteAggregateId() { return satelliteAggregateId; }
    public Integer getExecutionAggregateId() { return executionAggregateId; }
    public Integer getUserAggregateId() { return userAggregateId; }
    public Long getEventVersion() { return eventVersion; }
}
