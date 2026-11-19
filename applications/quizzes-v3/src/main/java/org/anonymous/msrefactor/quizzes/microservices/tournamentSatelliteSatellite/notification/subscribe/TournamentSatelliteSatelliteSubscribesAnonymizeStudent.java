package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.AnonymizeStudentEvent;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.TournamentParticipant;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.TournamentSatelliteSatellite;

import java.util.HashSet;
import java.util.Set;

public class TournamentSatelliteSatelliteSubscribesAnonymizeStudent extends EventSubscription {
    private final Set<Integer> memberAggregateIds = new HashSet<>();

    public TournamentSatelliteSatelliteSubscribesAnonymizeStudent(TournamentSatelliteSatellite tournamentSatelliteSatellite) {
        super(tournamentSatelliteSatellite.getCourseExecutionAggregateId(),
                tournamentSatelliteSatellite.getCourseExecutionVersion(),
                AnonymizeStudentEvent.class.getSimpleName());
        if (tournamentSatelliteSatellite.getCreatorAggregateId() != null) {
            this.memberAggregateIds.add(tournamentSatelliteSatellite.getCreatorAggregateId());
        }
        for (TournamentParticipant p : tournamentSatelliteSatellite.getTournamentParticipants()) {
            this.memberAggregateIds.add(p.getParticipantAggregateId());
        }
    }

    public TournamentSatelliteSatelliteSubscribesAnonymizeStudent() {
    }

    @Override
    public boolean subscribesEvent(Event event) {
        return super.subscribesEvent(event) && checkTournamentInfo((AnonymizeStudentEvent) event);
    }

    private boolean checkTournamentInfo(AnonymizeStudentEvent anonymizeStudentEvent) {
        return this.memberAggregateIds.contains(anonymizeStudentEvent.getStudentAggregateId());
    }
}
