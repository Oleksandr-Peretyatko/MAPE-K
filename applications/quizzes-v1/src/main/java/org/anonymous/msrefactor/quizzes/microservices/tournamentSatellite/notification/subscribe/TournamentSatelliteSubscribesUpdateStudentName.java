package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.UpdateStudentNameEvent;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentParticipant;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatellite;

import java.util.HashSet;
import java.util.Set;

public class TournamentSatelliteSubscribesUpdateStudentName extends EventSubscription {
    private final Set<Integer> memberAggregateIds = new HashSet<>();

    public TournamentSatelliteSubscribesUpdateStudentName(TournamentSatellite tournamentSatellite) {
        super(tournamentSatellite.getCourseExecutionAggregateId(),
                tournamentSatellite.getCourseExecutionVersion(),
                UpdateStudentNameEvent.class.getSimpleName());
        if (tournamentSatellite.getTournamentCreator() != null) {
            this.memberAggregateIds.add(tournamentSatellite.getTournamentCreator().getCreatorAggregateId());
        }
        for (TournamentParticipant p : tournamentSatellite.getTournamentParticipants()) {
            this.memberAggregateIds.add(p.getParticipantAggregateId());
        }
    }

    public TournamentSatelliteSubscribesUpdateStudentName() {
    }

    @Override
    public boolean subscribesEvent(Event event) {
        return super.subscribesEvent(event) && checkTournamentInfo((UpdateStudentNameEvent) event);
    }

    private boolean checkTournamentInfo(UpdateStudentNameEvent updateStudentNameEvent) {
        return this.memberAggregateIds.contains(updateStudentNameEvent.getStudentAggregateId());
    }
}
