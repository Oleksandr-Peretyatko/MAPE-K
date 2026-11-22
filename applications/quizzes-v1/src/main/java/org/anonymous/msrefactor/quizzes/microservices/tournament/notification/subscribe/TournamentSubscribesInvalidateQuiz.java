package org.anonymous.msrefactor.quizzes.microservices.tournament.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.InvalidateQuizEvent;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentQuiz;

public class TournamentSubscribesInvalidateQuiz extends EventSubscription {
    public TournamentSubscribesInvalidateQuiz(TournamentQuiz tournamentQuiz) {
        super(tournamentQuiz.getQuizAggregateId(),
                tournamentQuiz.getQuizVersion(),
                InvalidateQuizEvent.class.getSimpleName());
    }

    public TournamentSubscribesInvalidateQuiz() {}

    @Override
    public boolean subscribesEvent(Event event) {
         return super.subscribesEvent(event);
    }

}