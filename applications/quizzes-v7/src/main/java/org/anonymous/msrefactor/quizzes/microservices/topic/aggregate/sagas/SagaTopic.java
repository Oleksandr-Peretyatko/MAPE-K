package org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.sagas;

import jakarta.persistence.Entity;
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.GenericSagaState;
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.SagaAggregate;
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.Topic;
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.TopicCourse;
import jakarta.persistence.Convert;
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.SagaStateConverter;
@Entity
public class SagaTopic extends Topic implements SagaAggregate {
    @Convert(converter = SagaStateConverter.class)
    private SagaState sagaState;
    
    public SagaTopic() {
        super();
        this.sagaState = GenericSagaState.NOT_IN_SAGA;
    }

    public SagaTopic(Integer aggregateId, String name, TopicCourse topicCourse) {
        super(aggregateId, name, topicCourse);
        this.sagaState = GenericSagaState.NOT_IN_SAGA;
    }

    public SagaTopic(SagaTopic other) {
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
