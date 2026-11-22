package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.DisenrollStudentFromCourseExecutionEvent;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.TournamentParticipant;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.TournamentSatelliteSatellite;

import java.util.HashSet;
import java.util.Set;

public class TournamentSatelliteSatelliteSubscribesDisenrollStudentFromCourseExecution extends EventSubscription {
    private final Set<Integer> memberAggregateIds = new HashSet<>();

    public TournamentSatelliteSatelliteSubscribesDisenrollStudentFromCourseExecution(TournamentSatelliteSatellite tournamentSatelliteSatellite) {
        super(tournamentSatelliteSatellite.getCourseExecutionAggregateId(),
                tournamentSatelliteSatellite.getCourseExecutionVersion(),
                DisenrollStudentFromCourseExecutionEvent.class.getSimpleName());
        if (tournamentSatelliteSatellite.getCreatorAggregateId() != null) {
            this.memberAggregateIds.add(tournamentSatelliteSatellite.getCreatorAggregateId());
        }
        for (TournamentParticipant p : tournamentSatelliteSatellite.getTournamentParticipants()) {
            this.memberAggregateIds.add(p.getParticipantAggregateId());
        }
    }

    public TournamentSatelliteSatelliteSubscribesDisenrollStudentFromCourseExecution() {
    }

    @Override
    public boolean subscribesEvent(Event event) {
        return super.subscribesEvent(event) && checkTournamentInfo((DisenrollStudentFromCourseExecutionEvent) event);
    }

    private boolean checkTournamentInfo(DisenrollStudentFromCourseExecutionEvent disenrollStudentFromCourseExecutionEvent) {
        return this.memberAggregateIds.contains(disenrollStudentFromCourseExecutionEvent.getStudentAggregateId());
    }
}
