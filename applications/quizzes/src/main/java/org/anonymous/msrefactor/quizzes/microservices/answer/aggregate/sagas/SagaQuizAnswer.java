package org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.sagas;

import jakarta.persistence.Entity;
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.GenericSagaState;
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.SagaAggregate;
import org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.AnswerCourseExecution;
import org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.AnswerStudent;
import org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.AnsweredQuiz;
import org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.QuizAnswer;
import jakarta.persistence.Convert;
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.SagaStateConverter;
@Entity
public class SagaQuizAnswer extends QuizAnswer implements SagaAggregate {
    @Convert(converter = SagaStateConverter.class)
    private SagaState sagaState;
    
    public SagaQuizAnswer() {
        super();
        this.sagaState = GenericSagaState.NOT_IN_SAGA;
    }

    public SagaQuizAnswer(Integer aggregateId, AnswerCourseExecution answerCourseExecution, AnswerStudent answerStudent, AnsweredQuiz answeredQuiz) {
        super(aggregateId, answerCourseExecution, answerStudent, answeredQuiz);
        this.sagaState = GenericSagaState.NOT_IN_SAGA;
    }

    public SagaQuizAnswer(SagaQuizAnswer other) {
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
