package org.anonymous.msrefactor.quizzes.microservices.question.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.UpdateTopicEvent;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionTopic;

public class QuestionSubscribesUpdateTopic extends EventSubscription {
    public QuestionSubscribesUpdateTopic(QuestionTopic questionTopic) {
        super(questionTopic.getTopicAggregateId(),
                questionTopic.getTopicVersion(),
                UpdateTopicEvent.class.getSimpleName());
    }

    public QuestionSubscribesUpdateTopic() {}

    public boolean subscribesEvent(Event event) {
         return super.subscribesEvent(event);
    }

}