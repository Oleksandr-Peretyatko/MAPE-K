package org.anonymous.msrefactor.quizzes.commands.execution;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

public class EnrollStudentCommand extends Command {
    private Integer courseExecutionAggregateId;
    private UserDto userDto;

    public EnrollStudentCommand(UnitOfWork unitOfWork, String serviceName, Integer courseExecutionAggregateId, UserDto userDto) {
        super(unitOfWork, serviceName, courseExecutionAggregateId);
        this.courseExecutionAggregateId = courseExecutionAggregateId;
        this.userDto = userDto;
    }

    public Integer getCourseExecutionAggregateId() {
        return courseExecutionAggregateId;
    }

    public UserDto getUserDto() {
        return userDto;
    }
}
