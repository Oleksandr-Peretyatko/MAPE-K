package org.anonymous.msrefactor.quizzes.commands.topic;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.TopicCourse;
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.TopicDto;

public class CreateTopicCommand extends Command {
    private TopicDto topicDto;
    private TopicCourse course;

    public CreateTopicCommand(UnitOfWork unitOfWork, String serviceName, TopicDto topicDto, TopicCourse course) {
        super(unitOfWork, serviceName, null);
        this.topicDto = topicDto;
        this.course = course;
    }

    public TopicDto getTopicDto() { return topicDto; }
    public TopicCourse getCourse() { return course; }
}
