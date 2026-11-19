package org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.sagas.factories;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.Execution;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionCourse;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionFactory;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.Quiz;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.QuizQuestion;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.QuizType;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.sagas.SagaExecution;

import java.util.Set;

@Service
@Profile("sagas")
public class SagasCourseExecutionFactory implements CourseExecutionFactory {
    @Override
    public Execution createCourseExecution(Integer aggregateId, CourseExecutionDto courseExecutionDto, CourseExecutionCourse courseExecutionCourse) {
        return new SagaExecution(aggregateId, courseExecutionDto, courseExecutionCourse);
    }

    @Override
    public Execution createCourseExecutionFromExisting(Execution existingAnswer) {
        return new SagaExecution((SagaExecution) existingAnswer);
    }

    @Override
    public CourseExecutionDto createCourseExecutionDto(Execution execution) {
        return new CourseExecutionDto(execution);
    }

    @Override
    public Quiz createQuiz(Integer quizId, Set<QuizQuestion> quizQuestions, QuizDto quizDto, QuizType quizType) {
        return new Quiz(quizId, quizQuestions, quizDto, quizType);
    }

    @Override
    public QuizDto createQuizDto(Quiz quiz) {
        return new QuizDto(quiz);
    }
}
