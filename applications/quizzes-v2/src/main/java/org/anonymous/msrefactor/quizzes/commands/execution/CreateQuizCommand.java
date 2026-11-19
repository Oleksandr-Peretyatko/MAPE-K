package org.anonymous.msrefactor.quizzes.commands.execution;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionDto;

import java.util.Set;

public class CreateQuizCommand extends Command {
    private Integer courseExecutionAggregateId;
    private Set<QuestionDto> questions;
    private QuizDto quizDto;

    public CreateQuizCommand(UnitOfWork unitOfWork, String serviceName, Integer courseExecutionAggregateId, Set<QuestionDto> questions, QuizDto quizDto) {
        super(unitOfWork, serviceName, courseExecutionAggregateId);
        this.courseExecutionAggregateId = courseExecutionAggregateId;
        this.questions = questions;
        this.quizDto = quizDto;
    }

    public Integer getCourseExecutionAggregateId() { return courseExecutionAggregateId; }
    public Set<QuestionDto> getQuestions() { return questions; }
    public QuizDto getQuizDto() { return quizDto; }
}
