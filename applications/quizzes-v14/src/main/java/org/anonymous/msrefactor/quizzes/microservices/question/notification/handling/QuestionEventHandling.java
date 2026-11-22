package org.anonymous.msrefactor.quizzes.microservices.question.notification.handling;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.ms.aggregate.EventApplicationService;
import org.anonymous.msrefactor.ms.notification.EventHandling;
import org.anonymous.msrefactor.quizzes.events.DeleteTopicEvent;
import org.anonymous.msrefactor.quizzes.events.UpdateTopicEvent;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionRepository;
import org.anonymous.msrefactor.quizzes.microservices.question.coordination.eventProcessing.QuestionEventProcessing;
import org.anonymous.msrefactor.quizzes.microservices.question.notification.handling.handlers.DeleteTopicEventHandler;
import org.anonymous.msrefactor.quizzes.microservices.question.notification.handling.handlers.UpdateTopicEventHandler;

@Component
public class QuestionEventHandling implements EventHandling {
    @Autowired
    private EventApplicationService eventApplicationService;
    @Autowired
    private QuestionEventProcessing questionEventProcessing;
    @Autowired
    private QuestionRepository questionRepository;

    /*
        TOPICS_EXISTS
     */
    @Scheduled(fixedDelay = 1000)
    public void handleUpdateTopicEvents() {
        eventApplicationService.handleSubscribedEvent(UpdateTopicEvent.class,
                new UpdateTopicEventHandler(questionRepository, questionEventProcessing));
    }

    /*
        TOPICS_EXISTS
     */
    @Scheduled(fixedDelay = 1000)
    public void handleDeleteTopicEvents() {
        eventApplicationService.handleSubscribedEvent(DeleteTopicEvent.class,
                new DeleteTopicEventHandler(questionRepository, questionEventProcessing));
    }

    /*
        COURSE_EXISTS
     */
}
