package org.anonymous.msrefactor.quizzes.commands.course;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionTopic;

import java.util.Set;

public class UpdateQuestionTopicsCommand extends Command {
    private Integer courseAggregateId;
    private Set<QuestionTopic> topics;

    public UpdateQuestionTopicsCommand(UnitOfWork unitOfWork, String serviceName, Integer courseAggregateId, Set<QuestionTopic> topics) {
        super(unitOfWork, serviceName, null);
        this.courseAggregateId = courseAggregateId;
        this.topics = topics;
    }

    public Integer getCourseAggregateId() {
        return courseAggregateId;
    }

    public Set<QuestionTopic> getTopics() {
        return topics;
    }
}
