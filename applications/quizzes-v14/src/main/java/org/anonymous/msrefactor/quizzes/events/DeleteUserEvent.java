package org.anonymous.msrefactor.quizzes.events;

import jakarta.persistence.Entity;
import org.anonymous.msrefactor.ms.aggregate.Event;

@Entity
public class DeleteUserEvent extends Event {
    public DeleteUserEvent() {
        super();
    }

    public DeleteUserEvent(Integer userAggregateId) {
        super(userAggregateId);
    }
}




