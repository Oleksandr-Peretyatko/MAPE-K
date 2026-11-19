package org.anonymous.msrefactor.quizzes.commands.execution;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionDto;

import java.util.List;

public class GenerateQuizCommand extends Command {
    private CourseExecutionDto courseExecutionDto;
    private QuizDto quizDto;
    private List<QuestionDto> questionDtos;
    private Integer numberOfQuestions;

    public GenerateQuizCommand(UnitOfWork unitOfWork, String serviceName, CourseExecutionDto courseExecutionDto, QuizDto quizDto, List<QuestionDto> questionDtos, Integer numberOfQuestions) {
        super(unitOfWork, serviceName, courseExecutionDto.getAggregateId());
        this.courseExecutionDto = courseExecutionDto;
        this.quizDto = quizDto;
        this.questionDtos = questionDtos;
        this.numberOfQuestions = numberOfQuestions;
    }

    public QuizDto getQuizDto() { return quizDto; }
    public Integer getNumberOfQuestions() { return numberOfQuestions; }
    public List<QuestionDto> getQuestionDtos() {
        return questionDtos;
    }

    public CourseExecutionDto getCourseExecutionDto() {
        return courseExecutionDto;
    }
}
