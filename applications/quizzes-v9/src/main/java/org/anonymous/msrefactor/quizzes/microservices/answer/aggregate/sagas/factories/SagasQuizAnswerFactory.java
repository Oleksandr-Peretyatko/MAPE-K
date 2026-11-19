package org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.sagas.factories;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.*;
import org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.sagas.SagaQuizAnswer;

@Service
@Profile("sagas")
public class SagasQuizAnswerFactory implements QuizAnswerFactory {
    @Override
    public QuizAnswer createQuizAnswer(Integer aggregateId, AnswerCourseExecution answerCourseExecution, AnswerStudent answerStudent, AnsweredQuiz answeredQuiz) {
        return new SagaQuizAnswer(aggregateId, answerCourseExecution, answerStudent, answeredQuiz);
    }

    @Override
    public QuizAnswer createQuizAnswerFromExisting(QuizAnswer existingAnswer) {
        return new SagaQuizAnswer((SagaQuizAnswer) existingAnswer);
    }

    @Override
    public QuizAnswerDto createQuizAnswerDto(QuizAnswer quizAnswer) {
        return new QuizAnswerDto(quizAnswer);
    }
}
