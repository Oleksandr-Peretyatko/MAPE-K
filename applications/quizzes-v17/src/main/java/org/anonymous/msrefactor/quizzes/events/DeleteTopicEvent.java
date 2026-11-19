package org.anonymous.msrefactor.quizzes.events;

import jakarta.persistence.Entity;
import org.anonymous.msrefactor.ms.aggregate.Event;

@Entity
public class DeleteTopicEvent extends Event {
    public DeleteTopicEvent() {
        super();
    }
    public DeleteTopicEvent(Integer topicAggregateId) {
        super(topicAggregateId);
    }
}
