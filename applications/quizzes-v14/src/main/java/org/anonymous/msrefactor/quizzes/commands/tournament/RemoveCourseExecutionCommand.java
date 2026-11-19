package org.anonymous.msrefactor.quizzes.commands.tournament;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class RemoveCourseExecutionCommand extends Command {
    private final Integer tournamentAggregateId;
    private final Integer courseExecutionId;
    private final Long eventVersion;

    public RemoveCourseExecutionCommand(UnitOfWork unitOfWork, String serviceName, Integer tournamentAggregateId,
            Integer courseExecutionId, Long eventVersion) {
        super(unitOfWork, serviceName, tournamentAggregateId);
        this.tournamentAggregateId = tournamentAggregateId;
        this.courseExecutionId = courseExecutionId;
        this.eventVersion = eventVersion;
    }

    public Integer getTournamentAggregateId() {
        return tournamentAggregateId;
    }

    public Integer getCourseExecutionId() {
        return courseExecutionId;
    }

    public Long getEventVersion() {
        return eventVersion;
    }
}
