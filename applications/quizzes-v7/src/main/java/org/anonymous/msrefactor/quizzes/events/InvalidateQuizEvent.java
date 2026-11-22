package org.anonymous.msrefactor.quizzes.events;

import jakarta.persistence.Entity;
import org.anonymous.msrefactor.ms.aggregate.Event;

@Entity
public class InvalidateQuizEvent extends Event {
    public InvalidateQuizEvent() {
        super();
    }

    public InvalidateQuizEvent(Integer quizAggregateId) {
        super(quizAggregateId);
    }
}
