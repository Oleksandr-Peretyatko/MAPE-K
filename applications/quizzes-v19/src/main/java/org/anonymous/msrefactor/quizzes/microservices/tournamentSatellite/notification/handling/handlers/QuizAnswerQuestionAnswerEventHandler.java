package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.notification.handling.handlers;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.quizzes.events.QuizAnswerQuestionAnswerEvent;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatelliteRepository;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.coordination.eventProcessing.TournamentSatelliteEventProcessing;

public class QuizAnswerQuestionAnswerEventHandler extends TournamentSatelliteEventHandler {
    public QuizAnswerQuestionAnswerEventHandler(TournamentSatelliteRepository tournamentSatelliteRepository, TournamentSatelliteEventProcessing tournamentSatelliteEventProcessing) {
        super(tournamentSatelliteRepository, tournamentSatelliteEventProcessing);
    }

    @Override
    public void handleEvent(Integer subscriberAggregateId, Event event) {
        this.tournamentSatelliteEventProcessing.processAnswerQuestionEvent(subscriberAggregateId, (QuizAnswerQuestionAnswerEvent) event);
    }
}
