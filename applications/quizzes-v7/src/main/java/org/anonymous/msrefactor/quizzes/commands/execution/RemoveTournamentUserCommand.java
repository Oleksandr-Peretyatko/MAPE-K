package org.anonymous.msrefactor.quizzes.commands.execution;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class RemoveTournamentUserCommand extends Command {
    private final Integer tournamentAggregateId;
    private final Integer courseExecutionAggregateId;
    private final Integer userAggregateId;
    private final Long eventVersion;

    public RemoveTournamentUserCommand(UnitOfWork unitOfWork, String serviceName, Integer tournamentAggregateId,
            Integer courseExecutionAggregateId, Integer userAggregateId, Long eventVersion) {
        super(unitOfWork, serviceName, tournamentAggregateId);
        this.tournamentAggregateId = tournamentAggregateId;
        this.courseExecutionAggregateId = courseExecutionAggregateId;
        this.userAggregateId = userAggregateId;
        this.eventVersion = eventVersion;
    }

    public Integer getTournamentAggregateId() {
        return tournamentAggregateId;
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
