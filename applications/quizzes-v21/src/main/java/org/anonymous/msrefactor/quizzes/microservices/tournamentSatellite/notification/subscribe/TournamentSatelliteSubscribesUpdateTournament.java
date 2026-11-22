package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.UpdateTournamentEvent;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatellite;

/*
 * Refresh path for the replicated core scalars (startTime, cancelled) — the
 * TOURNAMENT_INFO inter-invariant introduced by the split.
 */
public class TournamentSatelliteSubscribesUpdateTournament extends EventSubscription {
    public TournamentSatelliteSubscribesUpdateTournament(TournamentSatellite tournamentSatellite) {
        super(tournamentSatellite.getTournamentAggregateId(),
                tournamentSatellite.getTournamentVersion(),
                UpdateTournamentEvent.class.getSimpleName());
    }

    public TournamentSatelliteSubscribesUpdateTournament() {}

    @Override
    public boolean subscribesEvent(Event event) {
         return super.subscribesEvent(event);
    }
}
