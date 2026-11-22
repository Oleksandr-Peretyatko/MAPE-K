package org.anonymous.msrefactor.quizzes.microservices.tournament.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.AnonymizeStudentEvent;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.Tournament;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

public class TournamentSubscribesAnonymizeStudent extends EventSubscription {
    private TournamentDto tournamentDto;

    public TournamentSubscribesAnonymizeStudent(Tournament tournament) {
        super(tournament.getTournamentCourseExecution().getCourseExecutionAggregateId(),
                tournament.getTournamentCourseExecution().getCourseExecutionVersion(),
                AnonymizeStudentEvent.class.getSimpleName());
        tournamentDto = new TournamentDto(tournament);
    }

    public TournamentSubscribesAnonymizeStudent() {}

    @Override
    public boolean subscribesEvent(Event event) {
         return super.subscribesEvent(event) && checkTournamentInfo((AnonymizeStudentEvent)event);
    }

    private boolean checkTournamentInfo(AnonymizeStudentEvent anonymizeStudentEvent) {
        if (tournamentDto.getCreator().getAggregateId().equals(anonymizeStudentEvent.getStudentAggregateId())) {
            return true;
        }

        for (UserDto tournamentParticipant: tournamentDto.getParticipants()) {
            if (tournamentParticipant.getAggregateId().equals(anonymizeStudentEvent.getStudentAggregateId())) {
                return true;
            }
        }

        return false;
    }
}