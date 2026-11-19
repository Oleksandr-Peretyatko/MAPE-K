package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.AnonymizeStudentEvent;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatellite;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatelliteDto;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

public class TournamentSatelliteSubscribesAnonymizeStudent extends EventSubscription {
    private TournamentSatelliteDto tournamentSatelliteDto;

    public TournamentSatelliteSubscribesAnonymizeStudent(TournamentSatellite tournamentSatellite) {
        super(tournamentSatellite.getCourseExecutionAggregateId(),
                tournamentSatellite.getCourseExecutionVersion(),
                AnonymizeStudentEvent.class.getSimpleName());
        tournamentSatelliteDto = tournamentSatellite.buildDto();
    }

    public TournamentSatelliteSubscribesAnonymizeStudent() {}

    @Override
    public boolean subscribesEvent(Event event) {
         return super.subscribesEvent(event) && checkTournamentInfo((AnonymizeStudentEvent) event);
    }

    private boolean checkTournamentInfo(AnonymizeStudentEvent anonymizeStudentEvent) {
        if (tournamentSatelliteDto.getCreator().getAggregateId().equals(anonymizeStudentEvent.getStudentAggregateId())) {
            return true;
        }

        for (UserDto tournamentParticipant : tournamentSatelliteDto.getParticipants()) {
            if (tournamentParticipant.getAggregateId().equals(anonymizeStudentEvent.getStudentAggregateId())) {
                return true;
            }
        }

        return false;
    }
}
