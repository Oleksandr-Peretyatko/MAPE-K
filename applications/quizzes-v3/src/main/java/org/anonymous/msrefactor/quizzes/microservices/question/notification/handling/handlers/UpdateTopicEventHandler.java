package org.anonymous.msrefactor.quizzes.microservices.question.notification.handling.handlers;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.quizzes.events.UpdateTopicEvent;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionRepository;
import org.anonymous.msrefactor.quizzes.microservices.question.coordination.eventProcessing.QuestionEventProcessing;

public class UpdateTopicEventHandler extends QuestionEventHandler {
    public UpdateTopicEventHandler(QuestionRepository questionRepository, QuestionEventProcessing questionEventProcessing) {
        super(questionRepository, questionEventProcessing);
    }

    @Override
    public void handleEvent(Integer subscriberAggregateId, Event event) {
        this.questionEventProcessing.processUpdateTopic(subscriberAggregateId, (UpdateTopicEvent) event);
    }
}
