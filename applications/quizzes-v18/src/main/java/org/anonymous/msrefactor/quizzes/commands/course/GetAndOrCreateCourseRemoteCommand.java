package org.anonymous.msrefactor.quizzes.commands.course;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;

public class GetAndOrCreateCourseRemoteCommand extends Command {
    private CourseExecutionDto courseExecutionDto;

    public GetAndOrCreateCourseRemoteCommand(UnitOfWork unitOfWork, String serviceName, CourseExecutionDto courseExecutionDto) {
        super(unitOfWork, serviceName, null);
        this.courseExecutionDto = courseExecutionDto;
    }

    public CourseExecutionDto getCourseExecutionDto() {
        return courseExecutionDto;
    }
}
