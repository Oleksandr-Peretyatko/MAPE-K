package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.UpdateTournamentEvent;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.TournamentSatelliteSatellite;

/*
 * Refresh path for the replicated tournament scalars (startTime, cancelled) —
 * the TOURNAMENT_INFO inter-invariant. The core TournamentSatellite keeps its
 * own copy of the same scalars and its own subscription; the replica is read by
 * both sides, so it is duplicated rather than re-pointed.
 */
public class TournamentSatelliteSatelliteSubscribesUpdateTournament extends EventSubscription {
    public TournamentSatelliteSatelliteSubscribesUpdateTournament(
            TournamentSatelliteSatellite tournamentSatelliteSatellite) {
        super(tournamentSatelliteSatellite.getTournamentAggregateId(),
                tournamentSatelliteSatellite.getTournamentVersion(),
                UpdateTournamentEvent.class.getSimpleName());
    }

    public TournamentSatelliteSatelliteSubscribesUpdateTournament() {}

    @Override
    public boolean subscribesEvent(Event event) {
         return super.subscribesEvent(event);
    }
}
