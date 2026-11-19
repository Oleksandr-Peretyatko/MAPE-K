package org.anonymous.msrefactor.quizzes.commands.tournamentSatelliteSatellite;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class AnonymizeUserCommand extends Command {
    private Integer satelliteAggregateId;
    private Integer executionAggregateId;
    private Integer userAggregateId;
    private String name;
    private String username;
    private Long eventVersion;

    public AnonymizeUserCommand(UnitOfWork unitOfWork, String serviceName, Integer satelliteAggregateId,
            Integer executionAggregateId, Integer userAggregateId, String name, String username, Long eventVersion) {
        super(unitOfWork, serviceName, satelliteAggregateId);
        this.satelliteAggregateId = satelliteAggregateId;
        this.executionAggregateId = executionAggregateId;
        this.userAggregateId = userAggregateId;
        this.name = name;
        this.username = username;
        this.eventVersion = eventVersion;
    }

    public Integer getSatelliteAggregateId() {
        return satelliteAggregateId;
    }

    public Integer getExecutionAggregateId() {
        return executionAggregateId;
    }

    public Integer getUserAggregateId() {
        return userAggregateId;
    }

    public String getName() {
        return name;
    }

    public String getUsername() {
        return username;
    }

    public Long getEventVersion() {
        return eventVersion;
    }
}
