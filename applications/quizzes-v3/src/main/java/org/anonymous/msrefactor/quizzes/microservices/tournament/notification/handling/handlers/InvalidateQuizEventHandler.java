package org.anonymous.msrefactor.quizzes.microservices.tournament.notification.handling.handlers;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.quizzes.events.InvalidateQuizEvent;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentRepository;
import org.anonymous.msrefactor.quizzes.microservices.tournament.coordination.eventProcessing.TournamentEventProcessing;

public class InvalidateQuizEventHandler extends TournamentEventHandler {
    public InvalidateQuizEventHandler(TournamentRepository tournamentRepository, TournamentEventProcessing tournamentEventProcessing) {
        super(tournamentRepository, tournamentEventProcessing);
    }

    @Override
    public void handleEvent(Integer subscriberAggregateId, Event event) {
        this.tournamentEventProcessing.processInvalidateQuizEvent(subscriberAggregateId, (InvalidateQuizEvent) event);
    }
}
