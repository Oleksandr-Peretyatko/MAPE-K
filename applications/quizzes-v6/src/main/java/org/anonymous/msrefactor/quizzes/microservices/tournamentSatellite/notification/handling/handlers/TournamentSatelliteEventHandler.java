package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.notification.handling.handlers;

import org.anonymous.msrefactor.ms.aggregate.EventHandler;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatelliteRepository;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.coordination.eventProcessing.TournamentSatelliteEventProcessing;

public abstract class TournamentSatelliteEventHandler extends EventHandler {
    protected TournamentSatelliteEventProcessing tournamentSatelliteEventProcessing;

    public TournamentSatelliteEventHandler(TournamentSatelliteRepository tournamentSatelliteRepository,
            TournamentSatelliteEventProcessing tournamentSatelliteEventProcessing) {
        super(tournamentSatelliteRepository);
        this.tournamentSatelliteEventProcessing = tournamentSatelliteEventProcessing;
    }
}
