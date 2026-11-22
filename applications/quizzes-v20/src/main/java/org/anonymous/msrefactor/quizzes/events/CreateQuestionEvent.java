package org.anonymous.msrefactor.quizzes.events;

import jakarta.persistence.Entity;
import org.anonymous.msrefactor.ms.aggregate.Event;

/**
 * Note: we pass `courseAggregateId` to super() so `publisherAggregateId` is the course id.
 * `publisherAggregateId` identifies the event stream that other aggregates subscribe to:
 * `EventSubscription.subscribesEvent` compares
 * event.getPublisherAggregateId() == subscription.getSubscribedAggregateId().
 * Using the question id here would make those subscriptions silently stop matching.
 */
@Entity
public class CreateQuestionEvent extends Event {
    private Integer courseAggregateId;

    public CreateQuestionEvent() {
        super();
    }

    public CreateQuestionEvent(Integer courseAggregateId) {
        super(courseAggregateId);
        this.courseAggregateId = courseAggregateId;
    }

    public Integer getCourseAggregateId() {
        return courseAggregateId;
    }

    public void setCourseAggregateId(Integer courseAggregateId) {
        this.courseAggregateId = courseAggregateId;
    }
}
