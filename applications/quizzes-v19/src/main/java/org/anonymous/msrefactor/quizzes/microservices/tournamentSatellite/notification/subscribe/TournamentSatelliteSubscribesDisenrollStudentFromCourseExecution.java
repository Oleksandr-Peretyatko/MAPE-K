package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.DisenrollStudentFromCourseExecutionEvent;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatellite;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatelliteDto;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

public class TournamentSatelliteSubscribesDisenrollStudentFromCourseExecution extends EventSubscription {
    private TournamentSatelliteDto tournamentSatelliteDto;

    public TournamentSatelliteSubscribesDisenrollStudentFromCourseExecution(TournamentSatellite tournamentSatellite) {
        super(tournamentSatellite.getCourseExecutionAggregateId(),
                tournamentSatellite.getCourseExecutionVersion(),
                DisenrollStudentFromCourseExecutionEvent.class.getSimpleName());
        this.tournamentSatelliteDto = tournamentSatellite.buildDto();
    }

    public TournamentSatelliteSubscribesDisenrollStudentFromCourseExecution() {}

    @Override
    public boolean subscribesEvent(Event event) {
         return super.subscribesEvent(event) && checkTournamentInfo((DisenrollStudentFromCourseExecutionEvent) event);
    }

    private boolean checkTournamentInfo(DisenrollStudentFromCourseExecutionEvent disenrollStudentFromCourseExecutionEvent) {
        if (tournamentSatelliteDto.getCreator().getAggregateId().equals(disenrollStudentFromCourseExecutionEvent.getStudentAggregateId())) {
            return true;
        }

        for (UserDto tournamentParticipant : tournamentSatelliteDto.getParticipants()) {
            if (tournamentParticipant.getAggregateId().equals(disenrollStudentFromCourseExecutionEvent.getStudentAggregateId())) {
                return true;
            }
        }

        return false;
    }
}
