package org.anonymous.msrefactor.quizzes.microservices.question.notification.handling.handlers;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.quizzes.events.DeleteTopicEvent;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionRepository;
import org.anonymous.msrefactor.quizzes.microservices.question.coordination.eventProcessing.QuestionEventProcessing;

public class DeleteTopicEventHandler extends QuestionEventHandler {
    public DeleteTopicEventHandler(QuestionRepository questionRepository, QuestionEventProcessing questionEventProcessing) {
        super(questionRepository, questionEventProcessing);
    }

    @Override
    public void handleEvent(Integer subscriberAggregateId, Event event) {
        this.questionEventProcessing.processDeleteTopic(subscriberAggregateId, (DeleteTopicEvent) event);
    }
}
