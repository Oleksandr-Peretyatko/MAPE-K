package org.anonymous.msrefactor.quizzes.microservices.question.aggregate.sagas.factories;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.*;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.sagas.SagaQuestion;

import java.util.List;

@Service
@Profile("sagas")
public class SagasQuestionFactory implements QuestionFactory {

    @Override
    public Question createQuestion(Integer aggregateId, QuestionCourse questionCourse, QuestionDto questionDto, List<QuestionTopic> questionTopics) {
        return new SagaQuestion(aggregateId, questionCourse, questionDto, questionTopics);
    }

    @Override
    public Question createQuestionFromExisting(Question existingQuestion) {
        return new SagaQuestion((SagaQuestion) existingQuestion);
    }

    @Override
    public QuestionDto createQuestionDto(Question question) {
        return new QuestionDto(question);
    }
    
}
