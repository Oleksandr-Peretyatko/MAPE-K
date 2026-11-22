package org.anonymous.msrefactor.quizzes.microservices.tournament.notification.handling.handlers;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.quizzes.events.QuizAnswerQuestionAnswerEvent;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentRepository;
import org.anonymous.msrefactor.quizzes.microservices.tournament.coordination.eventProcessing.TournamentEventProcessing;

public class QuizAnswerQuestionAnswerEventHandler extends TournamentEventHandler {
    public QuizAnswerQuestionAnswerEventHandler(TournamentRepository tournamentRepository, TournamentEventProcessing tournamentEventProcessing) {
        super(tournamentRepository, tournamentEventProcessing);
    }

    @Override
    public void handleEvent(Integer subscriberAggregateId, Event event) {
        this.tournamentEventProcessing.processAnswerQuestionEvent(subscriberAggregateId, (QuizAnswerQuestionAnswerEvent) event);
    }
}
