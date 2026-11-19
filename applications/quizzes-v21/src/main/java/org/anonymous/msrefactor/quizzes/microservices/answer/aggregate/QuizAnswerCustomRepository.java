package org.anonymous.msrefactor.quizzes.microservices.answer.aggregate;

import java.util.Optional;

public interface QuizAnswerCustomRepository {
    Optional<Integer> findQuizAnswerIdByQuizIdAndUserId(Integer quizAggregateId, Integer studentAggregateId);
    boolean existsByQuizIdAndStudentId(Integer quizAggregateId, Integer studentAggregateId);
}
