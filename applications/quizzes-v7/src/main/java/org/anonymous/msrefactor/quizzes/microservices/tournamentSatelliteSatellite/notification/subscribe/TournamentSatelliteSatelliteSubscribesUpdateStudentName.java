package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.UpdateStudentNameEvent;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.TournamentParticipant;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.TournamentSatelliteSatellite;

import java.util.HashSet;
import java.util.Set;

public class TournamentSatelliteSatelliteSubscribesUpdateStudentName extends EventSubscription {
    private final Set<Integer> memberAggregateIds = new HashSet<>();

    public TournamentSatelliteSatelliteSubscribesUpdateStudentName(TournamentSatelliteSatellite tournamentSatelliteSatellite) {
        super(tournamentSatelliteSatellite.getCourseExecutionAggregateId(),
                tournamentSatelliteSatellite.getCourseExecutionVersion(),
                UpdateStudentNameEvent.class.getSimpleName());
        if (tournamentSatelliteSatellite.getCreatorAggregateId() != null) {
            this.memberAggregateIds.add(tournamentSatelliteSatellite.getCreatorAggregateId());
        }
        for (TournamentParticipant p : tournamentSatelliteSatellite.getTournamentParticipants()) {
            this.memberAggregateIds.add(p.getParticipantAggregateId());
        }
    }

    public TournamentSatelliteSatelliteSubscribesUpdateStudentName() {
    }

    @Override
    public boolean subscribesEvent(Event event) {
        return super.subscribesEvent(event) && checkTournamentInfo((UpdateStudentNameEvent) event);
    }

    private boolean checkTournamentInfo(UpdateStudentNameEvent updateStudentNameEvent) {
        return this.memberAggregateIds.contains(updateStudentNameEvent.getStudentAggregateId());
    }
}
