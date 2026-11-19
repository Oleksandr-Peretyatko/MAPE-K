package org.anonymous.msrefactor.quizzes.commands.course;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class UpdateCourseQuestionCountCommand extends Command {
    private final Integer courseAggregateId;
    private final boolean increment;

    public UpdateCourseQuestionCountCommand(UnitOfWork unitOfWork, String serviceName,
            Integer courseAggregateId, boolean increment) {
        super(unitOfWork, serviceName, courseAggregateId);
        this.courseAggregateId = courseAggregateId;
        this.increment = increment;
    }

    public Integer getCourseAggregateId() {
        return courseAggregateId;
    }

    public boolean isIncrement() {
        return increment;
    }
}
