package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.notification.handling.handlers;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.quizzes.events.AnonymizeStudentEvent;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatelliteRepository;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.coordination.eventProcessing.TournamentSatelliteEventProcessing;

public class AnonymizeStudentEventHandler extends TournamentSatelliteEventHandler {
    public AnonymizeStudentEventHandler(TournamentSatelliteRepository tournamentSatelliteRepository, TournamentSatelliteEventProcessing tournamentSatelliteEventProcessing) {
        super(tournamentSatelliteRepository, tournamentSatelliteEventProcessing);
    }

    @Override
    public void handleEvent(Integer subscriberAggregateId, Event event) {
        this.tournamentSatelliteEventProcessing.processAnonymizeStudentEvent(subscriberAggregateId, (AnonymizeStudentEvent) event);
    }
}
