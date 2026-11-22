package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.eventProcessing;

import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.quizzes.events.DeleteTopicEvent;
import org.anonymous.msrefactor.quizzes.events.DeleteUserEvent;
import org.anonymous.msrefactor.quizzes.events.InvalidateQuizEvent;
import org.anonymous.msrefactor.quizzes.events.QuizAnswerQuestionAnswerEvent;
import org.anonymous.msrefactor.quizzes.events.UpdateTopicEvent;
import org.anonymous.msrefactor.quizzes.microservices.execution.coordination.functionalities.ExecutionFunctionalities;

@Service
public class ExecutionEventProcessing {
    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(ExecutionEventProcessing.class);

    @Autowired
    private ExecutionFunctionalities executionFunctionalities;

    public void processDeleteUserEvent(Integer aggregateId, DeleteUserEvent deleteUserEvent) {
        logger.info("Processing DeleteUserEvent: aggregateId={}, event={}", aggregateId, deleteUserEvent);
        executionFunctionalities.removeUserFromCourseExecution(aggregateId, deleteUserEvent.getPublisherAggregateId());
    }

    /* moved from tournament (merge execution <- tournament): the subscriber is the Execution root */

    public void processUpdateTopicEvent(Integer aggregateId, UpdateTopicEvent updateTopicEvent) {
        logger.info("Processing UpdateTopicEvent: aggregateId={}, event={}", aggregateId, updateTopicEvent);
        executionFunctionalities.updateTournamentTopic(aggregateId, updateTopicEvent);
    }

    public void processDeleteTopicEvent(Integer aggregateId, DeleteTopicEvent deleteTopicEvent) {
        logger.info("Processing DeleteTopicEvent: aggregateId={}, event={}", aggregateId, deleteTopicEvent);
        executionFunctionalities.deleteTournamentTopic(aggregateId, deleteTopicEvent);
    }

    public void processAnswerQuestionEvent(Integer aggregateId, QuizAnswerQuestionAnswerEvent quizAnswerQuestionAnswerEvent) {
        logger.info("Processing QuizAnswerQuestionAnswerEvent: aggregateId={}, event={}", aggregateId, quizAnswerQuestionAnswerEvent);
        executionFunctionalities.updateParticipantAnswer(aggregateId, quizAnswerQuestionAnswerEvent);
    }

    public void processInvalidateQuizEvent(Integer aggregateId, InvalidateQuizEvent invalidateQuizEvent) {
        logger.info("Processing InvalidateQuizEvent: aggregateId={}, event={}", aggregateId, invalidateQuizEvent);
        executionFunctionalities.invalidateQuiz(aggregateId, invalidateQuizEvent);
    }
}
