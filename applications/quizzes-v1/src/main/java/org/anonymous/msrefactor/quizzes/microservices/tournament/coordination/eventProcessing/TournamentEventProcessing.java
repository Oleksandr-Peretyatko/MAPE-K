package org.anonymous.msrefactor.quizzes.microservices.tournament.coordination.eventProcessing;

import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.quizzes.events.*;
import org.anonymous.msrefactor.quizzes.microservices.tournament.coordination.functionalities.TournamentFunctionalities;

@Service
public class TournamentEventProcessing {
    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(TournamentEventProcessing.class);

    @Autowired
    private TournamentFunctionalities tournamentFunctionalities;

    public void processRemoveCourseExecutionEvent(Integer aggregateId, DeleteCourseExecutionEvent deleteCourseExecutionEvent) {
        logger.info("Processing RemoveCourseExecutionEvent: aggregateId={}, event={}", aggregateId, deleteCourseExecutionEvent);
        tournamentFunctionalities.removeCourseExecution(aggregateId, deleteCourseExecutionEvent);
    }

    public void processUpdateTopicEvent(Integer aggregateId, UpdateTopicEvent updateTopicEvent) {
        logger.info("Processing UpdateTopicEvent: aggregateId={}, event={}", aggregateId, updateTopicEvent);
        tournamentFunctionalities.updateTopic(aggregateId, updateTopicEvent);
    }

    public void processDeleteTopicEvent(Integer aggregateId, DeleteTopicEvent deleteTopicEvent) {
        logger.info("Processing DeleteTopicEvent: aggregateId={}, event={}", aggregateId, deleteTopicEvent);
        tournamentFunctionalities.deleteTopic(aggregateId, deleteTopicEvent);
    }

    public void processInvalidateQuizEvent(Integer aggregateId, InvalidateQuizEvent invalidateQuizEvent) {
        logger.info("Processing InvalidateQuizEvent: aggregateId={}, event={}", aggregateId, invalidateQuizEvent);
        tournamentFunctionalities.invalidateQuiz(aggregateId, invalidateQuizEvent);
    }
}
