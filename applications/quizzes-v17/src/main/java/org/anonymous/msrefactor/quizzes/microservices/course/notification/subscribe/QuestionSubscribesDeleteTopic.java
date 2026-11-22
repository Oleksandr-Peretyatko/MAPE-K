package org.anonymous.msrefactor.quizzes.microservices.course.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.DeleteTopicEvent;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionTopic;

public class QuestionSubscribesDeleteTopic extends EventSubscription {
    public QuestionSubscribesDeleteTopic(QuestionTopic questionTopic) {
        super(questionTopic.getTopicAggregateId(),
                questionTopic.getTopicVersion(),
                DeleteTopicEvent.class.getSimpleName());
    }

    public QuestionSubscribesDeleteTopic() {}

    public boolean subscribesEvent(Event event) {
         return super.subscribesEvent(event);
    }

}