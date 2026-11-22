package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.UpdateStudentNameEvent;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatellite;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatelliteDto;

public class TournamentSatelliteSubscribesUpdateStudentName extends EventSubscription {
    private TournamentSatelliteDto tournamentSatelliteDto;

    public TournamentSatelliteSubscribesUpdateStudentName(TournamentSatellite tournamentSatellite) {
        super(tournamentSatellite.getCourseExecutionAggregateId(),
                tournamentSatellite.getCourseExecutionVersion(),
                UpdateStudentNameEvent.class.getSimpleName());
        tournamentSatelliteDto = tournamentSatellite.buildDto();
    }

    public TournamentSatelliteSubscribesUpdateStudentName() {}

    @Override
    public boolean subscribesEvent(Event event) {
         return super.subscribesEvent(event) && checkTournamentInfo((UpdateStudentNameEvent) event);
    }

    private boolean checkTournamentInfo(UpdateStudentNameEvent updateStudentNameEvent) {
        return tournamentSatelliteDto.getCreator().getAggregateId().equals(updateStudentNameEvent.getStudentAggregateId()) ||
                tournamentSatelliteDto.getParticipants().stream()
                        .anyMatch(participant -> participant.getAggregateId().equals(updateStudentNameEvent.getStudentAggregateId()));
    }
}
