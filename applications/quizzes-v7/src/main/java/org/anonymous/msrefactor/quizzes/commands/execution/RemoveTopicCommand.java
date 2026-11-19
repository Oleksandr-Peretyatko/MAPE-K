package org.anonymous.msrefactor.quizzes.commands.execution;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class RemoveTopicCommand extends Command {
    private final Integer executionAggregateId;
    private final Integer topicAggregateId;
    private final Long eventVersion;

    public RemoveTopicCommand(UnitOfWork unitOfWork, String serviceName, Integer executionAggregateId,
            Integer topicAggregateId, Long eventVersion) {
        super(unitOfWork, serviceName, executionAggregateId);
        this.executionAggregateId = executionAggregateId;
        this.topicAggregateId = topicAggregateId;
        this.eventVersion = eventVersion;
    }

    public Integer getExecutionAggregateId() {
        return executionAggregateId;
    }

    public Integer getTopicAggregateId() {
        return topicAggregateId;
    }

    public Long getEventVersion() {
        return eventVersion;
    }
}
