package org.anonymous.msrefactor.quizzes.commands.quiz;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class GetAvailableQuizzesCommand extends Command {
    private Integer courseExecutionAggregateId;

    public GetAvailableQuizzesCommand(UnitOfWork unitOfWork, String serviceName, Integer quizAggregateId, Integer courseExecutionAggregateId) {
        super(unitOfWork, serviceName, quizAggregateId);
        this.courseExecutionAggregateId = courseExecutionAggregateId;
    }

    public Integer getCourseExecutionAggregateId() {
        return courseExecutionAggregateId;
    }
}
