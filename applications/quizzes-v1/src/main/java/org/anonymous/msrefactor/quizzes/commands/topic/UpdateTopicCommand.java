package org.anonymous.msrefactor.quizzes.commands.topic;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.TopicDto;

public class UpdateTopicCommand extends Command {
    private TopicDto topicDto;

    public UpdateTopicCommand(UnitOfWork unitOfWork, String serviceName, TopicDto topicDto) {
        super(unitOfWork, serviceName, topicDto.getAggregateId());
        this.topicDto = topicDto;
    }

    public TopicDto getTopicDto() { return topicDto; }
}
