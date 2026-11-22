package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.coordination.eventProcessing;

import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.quizzes.events.*;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.coordination.functionalities.TournamentSatelliteFunctionalities;

@Service
public class TournamentSatelliteEventProcessing {
    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(TournamentSatelliteEventProcessing.class);

    @Autowired
    private TournamentSatelliteFunctionalities tournamentSatelliteFunctionalities;

    public void processAnonymizeStudentEvent(Integer aggregateId, AnonymizeStudentEvent anonymizeEvent) {
        logger.info("Processing AnonymizeStudentEvent: aggregateId={}, event={}", aggregateId, anonymizeEvent);
        tournamentSatelliteFunctionalities.anonymizeStudent(aggregateId, anonymizeEvent);
    }

    public void processAnswerQuestionEvent(Integer aggregateId, QuizAnswerQuestionAnswerEvent quizAnswerQuestionAnswerEvent) {
        logger.info("Processing QuizAnswerQuestionAnswerEvent: aggregateId={}, event={}", aggregateId, quizAnswerQuestionAnswerEvent);
        tournamentSatelliteFunctionalities.updateParticipantAnswer(aggregateId, quizAnswerQuestionAnswerEvent);
    }

    public void processDisenrollStudentFromCourseExecutionEvent(Integer aggregateId, DisenrollStudentFromCourseExecutionEvent disenrollStudentFromCourseExecutionEvent) {
        logger.info("Processing DisenrollStudentFromCourseExecutionEvent: aggregateId={}, event={}", aggregateId, disenrollStudentFromCourseExecutionEvent);
        tournamentSatelliteFunctionalities.disenrollStudent(aggregateId, disenrollStudentFromCourseExecutionEvent);
    }

    public void processUpdateStudentNameEvent(Integer subscriberAggregateId, UpdateStudentNameEvent updateStudentNameEvent) {
        logger.info("Processing UpdateStudentNameEvent: subscriberAggregateId={}, event={}", subscriberAggregateId, updateStudentNameEvent);
        tournamentSatelliteFunctionalities.updateStudentName(subscriberAggregateId, updateStudentNameEvent);
    }

    public void processUpdateTournamentEvent(Integer subscriberAggregateId, UpdateTournamentEvent updateTournamentEvent) {
        logger.info("Processing UpdateTournamentEvent: subscriberAggregateId={}, event={}", subscriberAggregateId, updateTournamentEvent);
        tournamentSatelliteFunctionalities.updateTournamentInfo(subscriberAggregateId, updateTournamentEvent);
    }
}
