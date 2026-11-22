package org.anonymous.msrefactor.quizzes.microservices.tournament.notification.handling;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.ms.aggregate.EventApplicationService;
import org.anonymous.msrefactor.ms.notification.EventHandling;
import org.anonymous.msrefactor.quizzes.events.*;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentRepository;
import org.anonymous.msrefactor.quizzes.microservices.tournament.coordination.eventProcessing.TournamentEventProcessing;
import org.anonymous.msrefactor.quizzes.microservices.tournament.notification.handling.handlers.*;

@Component
public class TournamentEventHandling implements EventHandling {
    @Autowired
    private EventApplicationService eventApplicationService;
    @Autowired
    private TournamentRepository tournamentRepository;
    @Autowired
    private TournamentEventProcessing tournamentEventProcessing;

    @Scheduled(fixedDelay = 1000)
    public void handleDeleteCourseExecutionEvents() {
        eventApplicationService.handleSubscribedEvent(DeleteCourseExecutionEvent.class,
                new DeleteCourseExecutionEventHandler(tournamentRepository, tournamentEventProcessing));
    }

    @Scheduled(fixedDelay = 1000)
    public void handleUpdateTopicEvents() {
        eventApplicationService.handleSubscribedEvent(UpdateTopicEvent.class,
                new UpdateTopicEventHandler(tournamentRepository, tournamentEventProcessing));
    }

    @Scheduled(fixedDelay = 1000)
    public void handleDeleteTopicEvents() {
        eventApplicationService.handleSubscribedEvent(DeleteTopicEvent.class,
                new DeleteTopicEventHandler(tournamentRepository, tournamentEventProcessing));
    }

    @Scheduled(fixedDelay = 1000)
    public void handleInvalidateQuizEvent() {
        eventApplicationService.handleSubscribedEvent(InvalidateQuizEvent.class,
                new InvalidateQuizEventHandler(tournamentRepository, tournamentEventProcessing));
    }
}
