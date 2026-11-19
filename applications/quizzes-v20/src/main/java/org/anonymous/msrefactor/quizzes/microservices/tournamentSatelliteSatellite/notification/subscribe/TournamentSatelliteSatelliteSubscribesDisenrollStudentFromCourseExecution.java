package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.DisenrollStudentFromCourseExecutionEvent;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.TournamentSatelliteSatellite;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.TournamentSatelliteSatelliteDto;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

public class TournamentSatelliteSatelliteSubscribesDisenrollStudentFromCourseExecution extends EventSubscription {
    private TournamentSatelliteSatelliteDto tournamentSatelliteSatelliteDto;

    public TournamentSatelliteSatelliteSubscribesDisenrollStudentFromCourseExecution(
            TournamentSatelliteSatellite tournamentSatelliteSatellite) {
        super(tournamentSatelliteSatellite.getCourseExecutionAggregateId(),
                tournamentSatelliteSatellite.getCourseExecutionVersion(),
                DisenrollStudentFromCourseExecutionEvent.class.getSimpleName());
        this.tournamentSatelliteSatelliteDto = tournamentSatelliteSatellite.buildDto();
    }

    public TournamentSatelliteSatelliteSubscribesDisenrollStudentFromCourseExecution() {}

    @Override
    public boolean subscribesEvent(Event event) {
         return super.subscribesEvent(event) && checkTournamentInfo((DisenrollStudentFromCourseExecutionEvent) event);
    }

    private boolean checkTournamentInfo(DisenrollStudentFromCourseExecutionEvent disenrollStudentFromCourseExecutionEvent) {
        for (UserDto tournamentParticipant : tournamentSatelliteSatelliteDto.getParticipants()) {
            if (tournamentParticipant.getAggregateId().equals(disenrollStudentFromCourseExecutionEvent.getStudentAggregateId())) {
                return true;
            }
        }

        return false;
    }
}
