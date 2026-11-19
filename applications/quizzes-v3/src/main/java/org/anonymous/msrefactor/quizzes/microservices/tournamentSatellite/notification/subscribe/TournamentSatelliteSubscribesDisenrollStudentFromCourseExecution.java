package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.DisenrollStudentFromCourseExecutionEvent;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatellite;

import java.util.HashSet;
import java.util.Set;

public class TournamentSatelliteSubscribesDisenrollStudentFromCourseExecution extends EventSubscription {
    private final Set<Integer> memberAggregateIds = new HashSet<>();

    public TournamentSatelliteSubscribesDisenrollStudentFromCourseExecution(TournamentSatellite tournamentSatellite) {
        super(tournamentSatellite.getCourseExecutionAggregateId(),
                tournamentSatellite.getCourseExecutionVersion(),
                DisenrollStudentFromCourseExecutionEvent.class.getSimpleName());
        if (tournamentSatellite.getTournamentCreator() != null) {
            this.memberAggregateIds.add(tournamentSatellite.getTournamentCreator().getCreatorAggregateId());
        }    }

    public TournamentSatelliteSubscribesDisenrollStudentFromCourseExecution() {
    }

    @Override
    public boolean subscribesEvent(Event event) {
        return super.subscribesEvent(event) && checkTournamentInfo((DisenrollStudentFromCourseExecutionEvent) event);
    }

    private boolean checkTournamentInfo(DisenrollStudentFromCourseExecutionEvent disenrollStudentFromCourseExecutionEvent) {
        return this.memberAggregateIds.contains(disenrollStudentFromCourseExecutionEvent.getStudentAggregateId());
    }
}
