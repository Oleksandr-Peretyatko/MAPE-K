package org.anonymous.msrefactor.quizzes.commands.execution;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class RemoveStudentFromCourseExecutionCommand extends Command {
    private Integer courseExecutionAggregateId;
    private Integer userAggregateId;

    public RemoveStudentFromCourseExecutionCommand(UnitOfWork unitOfWork, String serviceName, Integer courseExecutionAggregateId, Integer userAggregateId) {
        super(unitOfWork, serviceName, courseExecutionAggregateId);
        this.courseExecutionAggregateId = courseExecutionAggregateId;
        this.userAggregateId = userAggregateId;
    }

    public Integer getCourseExecutionAggregateId() {
        return courseExecutionAggregateId;
    }

    public Integer getUserAggregateId() {
        return userAggregateId;
    }
}
