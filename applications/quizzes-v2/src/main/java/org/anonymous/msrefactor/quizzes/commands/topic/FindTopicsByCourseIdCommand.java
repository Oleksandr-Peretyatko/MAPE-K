package org.anonymous.msrefactor.quizzes.commands.topic;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class FindTopicsByCourseIdCommand extends Command {
    private Integer courseAggregateId;

    public FindTopicsByCourseIdCommand(UnitOfWork unitOfWork, String serviceName, Integer courseAggregateId) {
        super(unitOfWork, serviceName, courseAggregateId);
        this.courseAggregateId = courseAggregateId;
    }

    public Integer getCourseAggregateId() {
        return courseAggregateId;
    }
}
