package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.notification.handling.handlers;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.quizzes.events.AnonymizeStudentEvent;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.TournamentSatelliteSatelliteRepository;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.coordination.eventProcessing.TournamentSatelliteSatelliteEventProcessing;

public class AnonymizeStudentEventHandler extends TournamentSatelliteSatelliteEventHandler {
    public AnonymizeStudentEventHandler(TournamentSatelliteSatelliteRepository tournamentSatelliteSatelliteRepository, TournamentSatelliteSatelliteEventProcessing tournamentSatelliteSatelliteEventProcessing) {
        super(tournamentSatelliteSatelliteRepository, tournamentSatelliteSatelliteEventProcessing);
    }

    @Override
    public void handleEvent(Integer subscriberAggregateId, Event event) {
        this.tournamentSatelliteSatelliteEventProcessing.processAnonymizeStudentEvent(subscriberAggregateId, (AnonymizeStudentEvent) event);
    }
}
