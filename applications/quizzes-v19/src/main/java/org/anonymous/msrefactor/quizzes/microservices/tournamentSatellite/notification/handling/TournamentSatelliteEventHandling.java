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

    /*
    CREATOR_EXISTS / PARTICIPANT_EXISTS
        anonymizeUser(user: User) renames and deactivates the creator/participant replicas
    */
    @Scheduled(fixedDelay = 1000)
    public void handleAnonymizeStudentEvents() {
        eventApplicationService.handleSubscribedEvent(AnonymizeStudentEvent.class,
                new AnonymizeStudentEventHandler(tournamentSatelliteRepository, tournamentSatelliteEventProcessing));
    }

    /*
        QUIZ_ANSWER_EXISTS
            p: this.participants | (!p.answer.isEmpty && p.answer.state != INACTIVE) => EXISTS QuizAnswer(p.answer.id)
     */
    @Scheduled(fixedDelay = 1000)
    public void handleAnswerQuestionEvent() {
        eventApplicationService.handleSubscribedEvent(QuizAnswerQuestionAnswerEvent.class,
                new QuizAnswerQuestionAnswerEventHandler(tournamentSatelliteRepository, tournamentSatelliteEventProcessing));
    }

    /*
        CREATOR_COURSE_EXECUTION
        PARTICIPANT_COURSE_EXECUTION
     */
    @Scheduled(fixedDelay = 1000)
    public void handleUnenrollStudentFromCourseExecutionEvents() {
        eventApplicationService.handleSubscribedEvent(DisenrollStudentFromCourseExecutionEvent.class,
                new DisenrollStudentFromCourseExecutionEventHandler(tournamentSatelliteRepository, tournamentSatelliteEventProcessing));
    }

    @Scheduled(fixedDelay = 1000)
    public void handleUpdateStudentNameEvent() {
        eventApplicationService.handleSubscribedEvent(UpdateStudentNameEvent.class,
                new UpdateStudentNameEventHandler(tournamentSatelliteRepository, tournamentSatelliteEventProcessing));
    }

    /*
        TOURNAMENT_INFO
            this.startTime == Tournament(this.tournamentAggregateId).startTime &&
            this.cancelled == Tournament(this.tournamentAggregateId).cancelled
    */
    @Scheduled(fixedDelay = 1000)
    public void handleUpdateTournamentEvents() {
        eventApplicationService.handleSubscribedEvent(UpdateTournamentEvent.class,
                new UpdateTournamentEventHandler(tournamentSatelliteRepository, tournamentSatelliteEventProcessing));
    }
}
