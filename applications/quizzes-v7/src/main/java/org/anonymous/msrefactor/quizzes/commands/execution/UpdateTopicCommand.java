package org.anonymous.msrefactor.quizzes.commands.execution;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class UpdateTopicCommand extends Command {
    private final Integer executionAggregateId;
    private final Integer topicAggregateId;
    private final String topicName;
    private final Long eventVersion;

    public UpdateTopicCommand(UnitOfWork unitOfWork, String serviceName, Integer executionAggregateId,
            Integer topicAggregateId, String topicName, Long eventVersion) {
        super(unitOfWork, serviceName, executionAggregateId);
        this.executionAggregateId = executionAggregateId;
        this.topicAggregateId = topicAggregateId;
        this.topicName = topicName;
        this.eventVersion = eventVersion;
    }

    public Integer getExecutionAggregateId() {
        return executionAggregateId;
    }

    public Integer getTopicAggregateId() {
        return topicAggregateId;
    }

    public String getTopicName() {
        return topicName;
    }

    public Long getEventVersion() {
        return eventVersion;
    }
}
