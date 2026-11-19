package org.anonymous.msrefactor.quizzes.commands.question;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class RemoveTopicCommand extends Command {
    private final Integer questionAggregateId;
    private final Integer topicAggregateId;
    private final Long aggregateVersion;

    public RemoveTopicCommand(UnitOfWork unitOfWork, String serviceName, Integer questionAggregateId,
            Integer topicAggregateId, Long aggregateVersion) {
        super(unitOfWork, serviceName, questionAggregateId);
        this.questionAggregateId = questionAggregateId;
        this.topicAggregateId = topicAggregateId;
        this.aggregateVersion = aggregateVersion;
    }

    public Integer getQuestionAggregateId() {
        return questionAggregateId;
    }

    public Integer getTopicAggregateId() {
        return topicAggregateId;
    }

    public Long getAggregateVersion() {
        return aggregateVersion;
    }
}
