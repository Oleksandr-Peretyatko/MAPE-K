package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.notification.handling;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.ms.aggregate.EventApplicationService;
import org.anonymous.msrefactor.ms.notification.EventHandling;
import org.anonymous.msrefactor.quizzes.events.*;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.TournamentSatelliteSatelliteRepository;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.coordination.eventProcessing.TournamentSatelliteSatelliteEventProcessing;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.notification.handling.handlers.*;

@Component
public class TournamentSatelliteSatelliteEventHandling implements EventHandling {
    @Autowired
    private EventApplicationService eventApplicationService;
    @Autowired
    private TournamentSatelliteSatelliteRepository tournamentSatelliteSatelliteRepository;
    @Autowired
    private TournamentSatelliteSatelliteEventProcessing tournamentSatelliteSatelliteEventProcessing;

    /*
    PARTICIPANT_EXISTS
        anonymizeUser(user: User) renames the participant replicas
    */
    @Scheduled(fixedDelay = 1000)
    public void handleAnonymizeStudentEvents() {
        eventApplicationService.handleSubscribedEvent(AnonymizeStudentEvent.class,
                new AnonymizeStudentEventHandler(tournamentSatelliteSatelliteRepository, tournamentSatelliteSatelliteEventProcessing));
    }

    /*
        QUIZ_ANSWER_EXISTS
            p: this.participants | (!p.answer.isEmpty && p.answer.state != INACTIVE) => EXISTS QuizAnswer(p.answer.id)
     */
    @Scheduled(fixedDelay = 1000)
    public void handleAnswerQuestionEvent() {
        eventApplicationService.handleSubscribedEvent(QuizAnswerQuestionAnswerEvent.class,
                new QuizAnswerQuestionAnswerEventHandler(tournamentSatelliteSatelliteRepository, tournamentSatelliteSatelliteEventProcessing));
    }

    /*
        PARTICIPANT_COURSE_EXECUTION
     */
    @Scheduled(fixedDelay = 1000)
    public void handleUnenrollStudentFromCourseExecutionEvents() {
        eventApplicationService.handleSubscribedEvent(DisenrollStudentFromCourseExecutionEvent.class,
                new DisenrollStudentFromCourseExecutionEventHandler(tournamentSatelliteSatelliteRepository, tournamentSatelliteSatelliteEventProcessing));
    }

    @Scheduled(fixedDelay = 1000)
    public void handleUpdateStudentNameEvent() {
        eventApplicationService.handleSubscribedEvent(UpdateStudentNameEvent.class,
                new UpdateStudentNameEventHandler(tournamentSatelliteSatelliteRepository, tournamentSatelliteSatelliteEventProcessing));
    }

    /*
        TOURNAMENT_INFO
            this.startTime == Tournament(this.tournamentAggregateId).startTime &&
            this.cancelled == Tournament(this.tournamentAggregateId).cancelled
    */
    @Scheduled(fixedDelay = 1000)
    public void handleUpdateTournamentEvents() {
        eventApplicationService.handleSubscribedEvent(UpdateTournamentEvent.class,
                new UpdateTournamentEventHandler(tournamentSatelliteSatelliteRepository, tournamentSatelliteSatelliteEventProcessing));
    }
}
