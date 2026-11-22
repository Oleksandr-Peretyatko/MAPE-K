package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.QuizAnswerQuestionAnswerEvent;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentParticipant;

public class TournamentSatelliteSubscribesAnswerQuestion extends EventSubscription {
    Integer studentAggregateId;

    public TournamentSatelliteSubscribesAnswerQuestion(TournamentParticipant tournamentParticipant) {
        super(tournamentParticipant.getParticipantAnswer().getQuizAnswerAggregateId(),
                tournamentParticipant.getParticipantAnswer().getQuizAnswerVersion(),
                QuizAnswerQuestionAnswerEvent.class.getSimpleName());
        this.studentAggregateId = tournamentParticipant.getParticipantAggregateId();
    }

    public TournamentSatelliteSubscribesAnswerQuestion() {
    }

    @Override
    public boolean subscribesEvent(Event event) {
        return super.subscribesEvent(event) && checkAnswerInfo((QuizAnswerQuestionAnswerEvent) event);
    }

    private boolean checkAnswerInfo(QuizAnswerQuestionAnswerEvent quizAnswerQuestionAnswerEvent) {
        return studentAggregateId.equals(quizAnswerQuestionAnswerEvent.getStudentAggregateId());
    }
}
