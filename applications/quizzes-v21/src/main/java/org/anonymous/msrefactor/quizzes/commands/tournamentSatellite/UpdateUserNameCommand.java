package org.anonymous.msrefactor.quizzes.commands.tournamentSatellite;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class UpdateUserNameCommand extends Command {
    private Integer satelliteAggregateId;
    private Integer executionAggregateId;
    private Long eventVersion;
    private Integer userAggregateId;
    private String name;

    public UpdateUserNameCommand(UnitOfWork unitOfWork, String serviceName, Integer satelliteAggregateId,
            Integer executionAggregateId, Long eventVersion, Integer userAggregateId, String name) {
        super(unitOfWork, serviceName, satelliteAggregateId);
        this.satelliteAggregateId = satelliteAggregateId;
        this.executionAggregateId = executionAggregateId;
        this.eventVersion = eventVersion;
        this.userAggregateId = userAggregateId;
        this.name = name;
    }

    public Integer getSatelliteAggregateId() {
        return satelliteAggregateId;
    }

    public Integer getExecutionAggregateId() {
        return executionAggregateId;
    }

    public Long getEventVersion() {
        return eventVersion;
    }

    public Integer getUserAggregateId() {
        return userAggregateId;
    }

    public String getName() {
        return name;
    }
}
