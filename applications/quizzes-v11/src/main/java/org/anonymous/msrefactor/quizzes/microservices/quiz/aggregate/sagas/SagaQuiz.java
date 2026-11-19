package org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.sagas;

import jakarta.persistence.Entity;
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.GenericSagaState;
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.SagaAggregate;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.*;

import java.util.Set;
import jakarta.persistence.Convert;
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.SagaStateConverter;
@Entity
public class SagaQuiz extends Quiz implements SagaAggregate {
    @Convert(converter = SagaStateConverter.class)
    private SagaState sagaState;
    
    public SagaQuiz() {
        this.sagaState = GenericSagaState.NOT_IN_SAGA;
    }

    public SagaQuiz(Integer aggregateId, QuizCourseExecution quizCourseExecution, Set<QuizQuestion> quizQuestions, QuizDto quizDto, QuizType quizType) {
        super(aggregateId, quizCourseExecution, quizQuestions, quizDto, quizType);
        this.sagaState = GenericSagaState.NOT_IN_SAGA;
    }

    public SagaQuiz(SagaQuiz other) {
        super(other);
        this.sagaState = other.getSagaState();
    }

    @Override
    public void setSagaState(SagaState state) {
        this.sagaState = state;
    }

    @Override
    public SagaState getSagaState() {
        return this.sagaState;
    }
}
