package org.anonymous.msrefactor.quizzes.microservices.tournament.notification.handling.handlers;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.quizzes.events.UpdateStudentNameEvent;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentRepository;
import org.anonymous.msrefactor.quizzes.microservices.tournament.coordination.eventProcessing.TournamentEventProcessing;

public class UpdateStudentNameEventHandler extends TournamentEventHandler {
    public UpdateStudentNameEventHandler(TournamentRepository tournamentRepository, TournamentEventProcessing tournamentEventProcessing) {
        super(tournamentRepository, tournamentEventProcessing);
    }

    @Override
    public void handleEvent(Integer subscriberAggregateId, Event event) {
        this.tournamentEventProcessing.processUpdateStudentNameEvent(subscriberAggregateId, (UpdateStudentNameEvent) event);
    }
}
