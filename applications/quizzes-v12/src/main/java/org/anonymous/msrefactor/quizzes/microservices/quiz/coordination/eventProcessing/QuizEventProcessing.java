package org.anonymous.msrefactor.quizzes.microservices.quiz.coordination.eventProcessing;

import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.quizzes.events.AnonymizeStudentEvent;
import org.anonymous.msrefactor.quizzes.events.DeleteCourseExecutionEvent;
import org.anonymous.msrefactor.quizzes.events.DeleteQuestionEvent;
import org.anonymous.msrefactor.quizzes.events.DeleteUserEvent;
import org.anonymous.msrefactor.quizzes.events.DisenrollStudentFromCourseExecutionEvent;
import org.anonymous.msrefactor.quizzes.events.UpdateQuestionEvent;
import org.anonymous.msrefactor.quizzes.events.UpdateStudentNameEvent;
import org.anonymous.msrefactor.quizzes.microservices.quiz.coordination.functionalities.QuizFunctionalities;

@Service
public class QuizEventProcessing {
    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(QuizEventProcessing.class);

    @Autowired
    private QuizFunctionalities quizFunctionalities;

    public void processDeleteCourseExecutionEvent(Integer aggregateId, DeleteCourseExecutionEvent deleteCourseExecutionEvent) {
        logger.info("Processing DeleteCourseExecutionEvent: aggregateId={}, event={}", aggregateId, deleteCourseExecutionEvent);
        quizFunctionalities.removeCourseExecutionFromQuiz(aggregateId, deleteCourseExecutionEvent);
    }

    public void processUpdateQuestionEvent(Integer aggregateId, UpdateQuestionEvent updateQuestionEvent) {
        logger.info("Processing UpdateQuestionEvent: aggregateId={}, event={}", aggregateId, updateQuestionEvent);
        quizFunctionalities.updateQuestionInQuiz(aggregateId, updateQuestionEvent);
    }

    public void processDeleteQuizQuestionEvent(Integer aggregateId, DeleteQuestionEvent deleteQuestionEvent) {
        logger.info("Processing DeleteQuizQuestionEvent: aggregateId={}, event={}", aggregateId, deleteQuestionEvent);
        quizFunctionalities.removeQuizQuestion(aggregateId, deleteQuestionEvent);
    }

    /************************************************
     * Absorbed from QuizAnswerEventProcessing (merge quiz <- answer): the subscriber id is now
     * the owning Quiz root. The former InvalidateQuizEvent and DeleteCourseExecutionEvent
     * consumptions are gone — the first became a synchronous flagging inside
     * QuizService.removeQuizQuestion, the second folded into processDeleteCourseExecutionEvent's
     * own service call.
     ************************************************/

    public void processDeleteUserEvent(Integer aggregateId, DeleteUserEvent deleteUserEvent) {
        logger.info("Processing DeleteUserEvent: aggregateId={}, event={}", aggregateId, deleteUserEvent);
        quizFunctionalities.removeUserFromQuizAnswers(aggregateId, deleteUserEvent.getPublisherAggregateId(), deleteUserEvent.getPublisherAggregateVersion());
    }

    public void processDisenrollStudentEvent(Integer aggregateId, DisenrollStudentFromCourseExecutionEvent disenrollStudentFromCourseExecutionEvent) {
        logger.info("Processing DisenrollStudentEvent: aggregateId={}, event={}", aggregateId, disenrollStudentFromCourseExecutionEvent);
        quizFunctionalities.removeUserFromQuizAnswers(aggregateId, disenrollStudentFromCourseExecutionEvent.getStudentAggregateId(), disenrollStudentFromCourseExecutionEvent.getPublisherAggregateVersion());
    }

    public void processUpdateStudentNameEvent(Integer subscriberAggregateId, UpdateStudentNameEvent updateStudentNameEvent) {
        logger.info("Processing UpdateStudentNameEvent: subscriberAggregateId={}, event={}", subscriberAggregateId, updateStudentNameEvent);
        quizFunctionalities.updateUserNameInQuizAnswers(subscriberAggregateId, updateStudentNameEvent.getPublisherAggregateId(), updateStudentNameEvent.getPublisherAggregateVersion(), updateStudentNameEvent.getStudentAggregateId(), updateStudentNameEvent.getUpdatedName());
    }

    public void processAnonymizeStudentEvent(Integer aggregateId, AnonymizeStudentEvent anonymizeStudentEvent) {
        logger.info("Processing AnonymizeStudentEvent: aggregateId={}, event={}", aggregateId, anonymizeStudentEvent);
        quizFunctionalities.updateUserNameInQuizAnswers(aggregateId, anonymizeStudentEvent.getPublisherAggregateId(),
                anonymizeStudentEvent.getPublisherAggregateVersion(), anonymizeStudentEvent.getStudentAggregateId(),
                anonymizeStudentEvent.getName());
    }

}
