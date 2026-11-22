package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.AnonymizeStudentEvent;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatellite;

import java.util.HashSet;
import java.util.Set;

public class TournamentSatelliteSubscribesAnonymizeStudent extends EventSubscription {
    private final Set<Integer> memberAggregateIds = new HashSet<>();

    public TournamentSatelliteSubscribesAnonymizeStudent(TournamentSatellite tournamentSatellite) {
        super(tournamentSatellite.getCourseExecutionAggregateId(),
                tournamentSatellite.getCourseExecutionVersion(),
                AnonymizeStudentEvent.class.getSimpleName());
        if (tournamentSatellite.getTournamentCreator() != null) {
            this.memberAggregateIds.add(tournamentSatellite.getTournamentCreator().getCreatorAggregateId());
        }    }

    public TournamentSatelliteSubscribesAnonymizeStudent() {
    }

    @Override
    public boolean subscribesEvent(Event event) {
        return super.subscribesEvent(event) && checkTournamentInfo((AnonymizeStudentEvent) event);
    }

    private boolean checkTournamentInfo(AnonymizeStudentEvent anonymizeStudentEvent) {
        return this.memberAggregateIds.contains(anonymizeStudentEvent.getStudentAggregateId());
    }
}
