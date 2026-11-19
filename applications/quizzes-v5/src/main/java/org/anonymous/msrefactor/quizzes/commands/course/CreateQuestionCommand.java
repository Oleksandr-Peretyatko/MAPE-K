package org.anonymous.msrefactor.quizzes.commands.course;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.TopicDto;

import java.util.List;

public class CreateQuestionCommand extends Command {
    private Integer courseAggregateId;
    private QuestionDto questionDto;
    private List<TopicDto> topics;

    public CreateQuestionCommand(UnitOfWork unitOfWork, String serviceName, Integer courseAggregateId, QuestionDto questionDto, List<TopicDto> topics) {
        super(unitOfWork, serviceName, courseAggregateId);
        this.courseAggregateId = courseAggregateId;
        this.questionDto = questionDto;
        this.topics = topics;
    }

    public Integer getCourseAggregateId() { return courseAggregateId; }
    public QuestionDto getQuestionDto() { return questionDto; }
    public List<TopicDto> getTopics() { return topics; }
}
