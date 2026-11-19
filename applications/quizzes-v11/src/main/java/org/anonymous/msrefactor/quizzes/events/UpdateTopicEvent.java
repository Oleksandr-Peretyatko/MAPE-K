package org.anonymous.msrefactor.quizzes.events;

import jakarta.persistence.Entity;
import org.anonymous.msrefactor.ms.aggregate.Event;

@Entity
public class UpdateTopicEvent extends Event {

    private String topicName;

    public UpdateTopicEvent() {
        super();
    }

    public UpdateTopicEvent(Integer topicAggregateId, String topicName) {
        super(topicAggregateId);
        setTopicName(topicName);
    }

    public String getTopicName() {
        return topicName;
    }

    public void setTopicName(String name) {
        this.topicName = name;
    }
}
