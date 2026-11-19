package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.UpdateStudentNameEvent;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.TournamentSatelliteSatellite;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.TournamentSatelliteSatelliteDto;

public class TournamentSatelliteSatelliteSubscribesUpdateStudentName extends EventSubscription {
    private TournamentSatelliteSatelliteDto tournamentSatelliteSatelliteDto;

    public TournamentSatelliteSatelliteSubscribesUpdateStudentName(
            TournamentSatelliteSatellite tournamentSatelliteSatellite) {
        super(tournamentSatelliteSatellite.getCourseExecutionAggregateId(),
                tournamentSatelliteSatellite.getCourseExecutionVersion(),
                UpdateStudentNameEvent.class.getSimpleName());
        tournamentSatelliteSatelliteDto = tournamentSatelliteSatellite.buildDto();
    }

    public TournamentSatelliteSatelliteSubscribesUpdateStudentName() {}

    @Override
    public boolean subscribesEvent(Event event) {
         return super.subscribesEvent(event) && checkTournamentInfo((UpdateStudentNameEvent) event);
    }

    private boolean checkTournamentInfo(UpdateStudentNameEvent updateStudentNameEvent) {
        return tournamentSatelliteSatelliteDto.getParticipants().stream()
                .anyMatch(participant -> participant.getAggregateId().equals(updateStudentNameEvent.getStudentAggregateId()));
    }
}
