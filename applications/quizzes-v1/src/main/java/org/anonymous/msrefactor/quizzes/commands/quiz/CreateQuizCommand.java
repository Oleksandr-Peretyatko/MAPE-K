package org.anonymous.msrefactor.quizzes.commands.quiz;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizCourseExecution;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizDto;

import java.util.Set;

public class CreateQuizCommand extends Command {
    private QuizCourseExecution quizCourseExecution;
    private Set<QuestionDto> questions;
    private QuizDto quizDto;

    public CreateQuizCommand(UnitOfWork unitOfWork, String serviceName, QuizCourseExecution quizCourseExecution, Set<QuestionDto> questions, QuizDto quizDto) {
        super(unitOfWork, serviceName, null);
        this.quizCourseExecution = quizCourseExecution;
        this.questions = questions;
        this.quizDto = quizDto;
    }

    public QuizCourseExecution getQuizCourseExecution() { return quizCourseExecution; }
    public Set<QuestionDto> getQuestions() { return questions; }
    public QuizDto getQuizDto() { return quizDto; }
}
