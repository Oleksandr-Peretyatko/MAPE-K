package org.anonymous.msrefactor.quizzes.commands.answer;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

public class StartQuizCommand extends Command {
    private Integer quizAggregateId;
    private Integer courseExecutionAggregateId;
    private final QuizDto quizDto;
    private final UserDto userDto;

    public StartQuizCommand(UnitOfWork unitOfWork, String serviceName, Integer quizAggregateId, Integer courseExecutionAggregateId, QuizDto quizDto, UserDto userDto) {
        super(unitOfWork, serviceName, quizAggregateId);
        this.quizAggregateId = quizAggregateId;
        this.courseExecutionAggregateId = courseExecutionAggregateId;
        this.quizDto = quizDto;
        this.userDto = userDto;
    }

    public Integer getQuizAggregateId() { return quizAggregateId; }

    public QuizDto getQuizDto() {
        return quizDto;
    }

    public UserDto getUserDto() {
        return userDto;
    }

    public Integer getCourseExecutionAggregateId() {
        return courseExecutionAggregateId;
    }
}
