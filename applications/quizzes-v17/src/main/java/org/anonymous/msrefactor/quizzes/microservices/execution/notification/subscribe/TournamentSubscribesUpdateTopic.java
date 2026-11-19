package org.anonymous.msrefactor.quizzes.microservices.execution.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.UpdateTopicEvent;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.TournamentTopic;

public class TournamentSubscribesUpdateTopic extends EventSubscription {
    public TournamentSubscribesUpdateTopic(TournamentTopic tournamentTopic) {
        super(tournamentTopic.getTopicAggregateId(),
                tournamentTopic.getTopicVersion(),
                UpdateTopicEvent.class.getSimpleName());
    }

    public TournamentSubscribesUpdateTopic() {}

    @Override
    public boolean subscribesEvent(Event event) {
         return super.subscribesEvent(event);
    }

}