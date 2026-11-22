package org.anonymous.msrefactor.quizzes.microservices.tournament.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.DisenrollStudentFromCourseExecutionEvent;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.Tournament;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

public class TournamentSubscribesDisenrollStudentFromCourseExecution extends EventSubscription {
    private TournamentDto tournamentDto;

    public TournamentSubscribesDisenrollStudentFromCourseExecution(Tournament tournament) {
        super(tournament.getTournamentCourseExecution().getCourseExecutionAggregateId(),
                tournament.getTournamentCourseExecution().getCourseExecutionVersion(),
                DisenrollStudentFromCourseExecutionEvent.class.getSimpleName());
        this.tournamentDto = new TournamentDto(tournament);
    }

    public TournamentSubscribesDisenrollStudentFromCourseExecution() {}

    @Override
    public boolean subscribesEvent(Event event) {
         return super.subscribesEvent(event) && checkTournamentInfo((DisenrollStudentFromCourseExecutionEvent)event);
    }

    private boolean checkTournamentInfo(DisenrollStudentFromCourseExecutionEvent disenrollStudentFromCourseExecutionEvent) {
        if (tournamentDto.getCreator().getAggregateId().equals(disenrollStudentFromCourseExecutionEvent.getStudentAggregateId())) {
            return true;
        }

        for (UserDto tournamentParticipant : tournamentDto.getParticipants()) {
            if (tournamentParticipant.getAggregateId().equals(disenrollStudentFromCourseExecutionEvent.getStudentAggregateId())) {
                return true;
            }
        }

        return false;
    }

}