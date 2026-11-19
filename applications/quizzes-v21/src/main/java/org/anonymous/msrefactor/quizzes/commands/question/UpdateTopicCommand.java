package org.anonymous.msrefactor.quizzes.commands.question;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class UpdateTopicCommand extends Command {
    private final Integer questionAggregateId;
    private final Integer topicAggregateId;
    private final String topicName;
    private final Long aggregateVersion;

    public UpdateTopicCommand(UnitOfWork unitOfWork, String serviceName, Integer questionAggregateId,
            Integer topicAggregateId, String topicName, Long aggregateVersion) {
        super(unitOfWork, serviceName, questionAggregateId);
        this.questionAggregateId = questionAggregateId;
        this.topicAggregateId = topicAggregateId;
        this.topicName = topicName;
        this.aggregateVersion = aggregateVersion;
    }

    public Integer getQuestionAggregateId() {
        return questionAggregateId;
    }

    public Integer getTopicAggregateId() {
        return topicAggregateId;
    }

    public String getTopicName() {
        return topicName;
    }

    public Long getAggregateVersion() {
        return aggregateVersion;
    }
}
