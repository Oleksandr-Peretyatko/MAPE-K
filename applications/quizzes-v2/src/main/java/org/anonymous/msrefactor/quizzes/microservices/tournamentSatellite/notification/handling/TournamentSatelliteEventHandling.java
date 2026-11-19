package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.notification.handling;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.ms.aggregate.EventApplicationService;
import org.anonymous.msrefactor.ms.notification.EventHandling;
import org.anonymous.msrefactor.quizzes.events.*;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatelliteRepository;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.coordination.eventProcessing.TournamentSatelliteEventProcessing;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.notification.handling.handlers.*;

@Component
public class TournamentSatelliteEventHandling implements EventHandling {
    @Autowired
    private EventApplicationService eventApplicationService;
    @Autowired
    private TournamentSatelliteRepository tournamentSatelliteRepository;
    @Autowired
    private TournamentSatelliteEventProcessing tournamentSatelliteEventProcessing;

    @Scheduled(fixedDelay = 1000)
    public void handleAnonymizeStudentEvents() {
        eventApplicationService.handleSubscribedEvent(AnonymizeStudentEvent.class,
                new AnonymizeStudentEventHandler(tournamentSatelliteRepository, tournamentSatelliteEventProcessing));
    }

    @Scheduled(fixedDelay = 1000)
    public void handleUpdateStudentNameEvent() {
        eventApplicationService.handleSubscribedEvent(UpdateStudentNameEvent.class,
                new UpdateStudentNameEventHandler(tournamentSatelliteRepository, tournamentSatelliteEventProcessing));
    }

    @Scheduled(fixedDelay = 1000)
    public void handleUnenrollStudentFromCourseExecutionEvents() {
        eventApplicationService.handleSubscribedEvent(DisenrollStudentFromCourseExecutionEvent.class,
                new DisenrollStudentFromCourseExecutionEventHandler(tournamentSatelliteRepository, tournamentSatelliteEventProcessing));
    }

    @Scheduled(fixedDelay = 1000)
    public void handleAnswerQuestionEvent() {
        eventApplicationService.handleSubscribedEvent(QuizAnswerQuestionAnswerEvent.class,
                new QuizAnswerQuestionAnswerEventHandler(tournamentSatelliteRepository, tournamentSatelliteEventProcessing));
    }
}
