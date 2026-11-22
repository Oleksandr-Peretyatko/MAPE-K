package org.anonymous.msrefactor.quizzes.microservices.tournament.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.UpdateStudentNameEvent;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.Tournament;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto;

public class TournamentSubscribesUpdateStudentName extends EventSubscription {
    private TournamentDto tournamentDto;

    public TournamentSubscribesUpdateStudentName(Tournament tournament) {
        super(tournament.getTournamentCourseExecution().getCourseExecutionAggregateId(),
                tournament.getTournamentCourseExecution().getCourseExecutionVersion(),
                UpdateStudentNameEvent.class.getSimpleName());
        tournamentDto = new TournamentDto(tournament);
    }

    public TournamentSubscribesUpdateStudentName() {}

    @Override
    public boolean subscribesEvent(Event event) {
         return super.subscribesEvent(event) && checkTournamentInfo((UpdateStudentNameEvent)event);
    }

    private boolean checkTournamentInfo(UpdateStudentNameEvent updateStudentNameEvent) {
        return tournamentDto.getCreator().getAggregateId().equals(updateStudentNameEvent.getStudentAggregateId()) ||
                tournamentDto.getParticipants().stream()
                        .anyMatch(participant -> participant.getAggregateId().equals(updateStudentNameEvent.getStudentAggregateId()));
//        if (tournamentDto.getCreator().getAggregateId().equals(updateStudentNameEvent.getStudentAggregateId())) {
//            return true;
//        }
//
//        for (UserDto tournamentParticipant : tournamentDto.getParticipants()) {
//            if (tournamentParticipant.getAggregateId().equals(updateStudentNameEvent.getStudentAggregateId())) {
//                return true;
//            }
//        }

//        return false;
    }

}