package org.anonymous.msrefactor.quizzes.microservices.question.aggregate.sagas.repositories;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionCustomRepository;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionRepository;


@Service
@Profile("sagas")
public class QuestionCustomRepositorySagas implements QuestionCustomRepository {

    @Autowired
    private QuestionRepository questionRepository;
}
