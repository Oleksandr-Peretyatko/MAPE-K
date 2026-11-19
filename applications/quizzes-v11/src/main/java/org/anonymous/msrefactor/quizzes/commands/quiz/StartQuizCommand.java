package org.anonymous.msrefactor.quizzes.commands.quiz;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

// The QuizDto parameter is gone (merge quiz <- answer): it carried a copy of the quiz into the
// answer service, and the handler now reads the authoritative Quiz root in-process.
public class StartQuizCommand extends Command {
    private Integer quizAggregateId;
    private Integer courseExecutionAggregateId;
    private final UserDto userDto;

    public StartQuizCommand(UnitOfWork unitOfWork, String serviceName, Integer quizAggregateId, Integer courseExecutionAggregateId, UserDto userDto) {
        super(unitOfWork, serviceName, quizAggregateId);
        this.quizAggregateId = quizAggregateId;
        this.courseExecutionAggregateId = courseExecutionAggregateId;
        this.userDto = userDto;
    }

    public Integer getQuizAggregateId() { return quizAggregateId; }

    public UserDto getUserDto() {
        return userDto;
    }

    public Integer getCourseExecutionAggregateId() {
        return courseExecutionAggregateId;
    }
}
