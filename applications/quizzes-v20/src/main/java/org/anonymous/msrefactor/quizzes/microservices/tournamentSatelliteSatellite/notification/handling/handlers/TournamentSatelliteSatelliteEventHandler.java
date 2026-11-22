package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.notification.handling.handlers;

import org.anonymous.msrefactor.ms.aggregate.EventHandler;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.TournamentSatelliteSatelliteRepository;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.coordination.eventProcessing.TournamentSatelliteSatelliteEventProcessing;

public abstract class TournamentSatelliteSatelliteEventHandler extends EventHandler {
    protected TournamentSatelliteSatelliteEventProcessing tournamentSatelliteSatelliteEventProcessing;

    public TournamentSatelliteSatelliteEventHandler(
            TournamentSatelliteSatelliteRepository tournamentSatelliteSatelliteRepository,
            TournamentSatelliteSatelliteEventProcessing tournamentSatelliteSatelliteEventProcessing) {
        super(tournamentSatelliteSatelliteRepository);
        this.tournamentSatelliteSatelliteEventProcessing = tournamentSatelliteSatelliteEventProcessing;
    }
}
