package org.anonymous.msrefactor.quizzes.events;

import jakarta.persistence.Entity;
import org.anonymous.msrefactor.ms.aggregate.Event;

@Entity
public class DeleteCourseExecutionEvent extends Event {
    public DeleteCourseExecutionEvent() {}

    public DeleteCourseExecutionEvent(Integer aggregateId) {
        super(aggregateId);
    }
}
