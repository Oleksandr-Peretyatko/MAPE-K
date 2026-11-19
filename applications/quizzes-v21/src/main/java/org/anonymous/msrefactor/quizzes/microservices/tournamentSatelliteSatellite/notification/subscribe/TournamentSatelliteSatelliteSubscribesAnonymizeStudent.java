package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.AnonymizeStudentEvent;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.TournamentSatelliteSatellite;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.TournamentSatelliteSatelliteDto;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

/*
 * The participant branch of the student-event subscriptions — the creator branch
 * stays on the core TournamentSatellite, which subscribes independently.
 */
public class TournamentSatelliteSatelliteSubscribesAnonymizeStudent extends EventSubscription {
    private TournamentSatelliteSatelliteDto tournamentSatelliteSatelliteDto;

    public TournamentSatelliteSatelliteSubscribesAnonymizeStudent(
            TournamentSatelliteSatellite tournamentSatelliteSatellite) {
        super(tournamentSatelliteSatellite.getCourseExecutionAggregateId(),
                tournamentSatelliteSatellite.getCourseExecutionVersion(),
                AnonymizeStudentEvent.class.getSimpleName());
        tournamentSatelliteSatelliteDto = tournamentSatelliteSatellite.buildDto();
    }

    public TournamentSatelliteSatelliteSubscribesAnonymizeStudent() {}

    @Override
    public boolean subscribesEvent(Event event) {
         return super.subscribesEvent(event) && checkTournamentInfo((AnonymizeStudentEvent) event);
    }

    private boolean checkTournamentInfo(AnonymizeStudentEvent anonymizeStudentEvent) {
        for (UserDto tournamentParticipant : tournamentSatelliteSatelliteDto.getParticipants()) {
            if (tournamentParticipant.getAggregateId().equals(anonymizeStudentEvent.getStudentAggregateId())) {
                return true;
            }
        }

        return false;
    }
}
