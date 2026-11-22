package org.anonymous.msrefactor.quizzes.commands.topic;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class DeleteTopicCommand extends Command {
    private Integer topicAggregateId;

    public DeleteTopicCommand(UnitOfWork unitOfWork, String serviceName, Integer topicAggregateId) {
        super(unitOfWork, serviceName, topicAggregateId);
        this.topicAggregateId = topicAggregateId;
    }

    public Integer getTopicAggregateId() {
        return topicAggregateId;
    }
}
