package org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.sagas;

import jakarta.persistence.Entity;
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.GenericSagaState;
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.SagaAggregate;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionCourse;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.Execution;
import jakarta.persistence.Convert;
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.SagaStateConverter;

@Entity
public class SagaExecution extends Execution implements SagaAggregate {
    @Convert(converter = SagaStateConverter.class)
    private SagaState sagaState;
    
    public SagaExecution() {
        super();
        this.sagaState = GenericSagaState.NOT_IN_SAGA;
    }

    public SagaExecution(Integer aggregateId, CourseExecutionDto courseExecutionDto, CourseExecutionCourse courseExecutionCourse) {
        super(aggregateId, courseExecutionDto, courseExecutionCourse);
        this.sagaState = GenericSagaState.NOT_IN_SAGA;
    }

    public SagaExecution(SagaExecution other) {
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
