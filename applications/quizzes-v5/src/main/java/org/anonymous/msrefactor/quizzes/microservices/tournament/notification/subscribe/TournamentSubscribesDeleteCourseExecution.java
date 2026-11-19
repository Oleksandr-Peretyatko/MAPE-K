package org.anonymous.msrefactor.quizzes.microservices.tournament.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.DeleteCourseExecutionEvent;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentCourseExecution;

public class TournamentSubscribesDeleteCourseExecution extends EventSubscription {
    public TournamentSubscribesDeleteCourseExecution(TournamentCourseExecution tournamentCourseExecution) {
        super(tournamentCourseExecution.getCourseExecutionAggregateId(),
                tournamentCourseExecution.getCourseExecutionVersion(),
                DeleteCourseExecutionEvent.class.getSimpleName());
    }

    public TournamentSubscribesDeleteCourseExecution() {}

    @Override
    public boolean subscribesEvent(Event event) {
         return super.subscribesEvent(event);
    }

}