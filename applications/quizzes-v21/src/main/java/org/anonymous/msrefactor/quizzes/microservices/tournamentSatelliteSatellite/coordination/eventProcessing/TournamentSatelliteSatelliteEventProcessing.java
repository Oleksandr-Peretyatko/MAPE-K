package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.coordination.eventProcessing;

import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.quizzes.events.*;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.coordination.functionalities.TournamentSatelliteSatelliteFunctionalities;

@Service
public class TournamentSatelliteSatelliteEventProcessing {
    private static final Logger logger = org.slf4j.LoggerFactory
            .getLogger(TournamentSatelliteSatelliteEventProcessing.class);

    @Autowired
    private TournamentSatelliteSatelliteFunctionalities tournamentSatelliteSatelliteFunctionalities;

    public void processAnonymizeStudentEvent(Integer aggregateId, AnonymizeStudentEvent anonymizeEvent) {
        logger.info("Processing AnonymizeStudentEvent: aggregateId={}, event={}", aggregateId, anonymizeEvent);
        tournamentSatelliteSatelliteFunctionalities.anonymizeStudent(aggregateId, anonymizeEvent);
    }

    public void processAnswerQuestionEvent(Integer aggregateId, QuizAnswerQuestionAnswerEvent quizAnswerQuestionAnswerEvent) {
        logger.info("Processing QuizAnswerQuestionAnswerEvent: aggregateId={}, event={}", aggregateId, quizAnswerQuestionAnswerEvent);
        tournamentSatelliteSatelliteFunctionalities.updateParticipantAnswer(aggregateId, quizAnswerQuestionAnswerEvent);
    }

    public void processDisenrollStudentFromCourseExecutionEvent(Integer aggregateId, DisenrollStudentFromCourseExecutionEvent disenrollStudentFromCourseExecutionEvent) {
        logger.info("Processing DisenrollStudentFromCourseExecutionEvent: aggregateId={}, event={}", aggregateId, disenrollStudentFromCourseExecutionEvent);
        tournamentSatelliteSatelliteFunctionalities.disenrollStudent(aggregateId, disenrollStudentFromCourseExecutionEvent);
    }

    public void processUpdateStudentNameEvent(Integer subscriberAggregateId, UpdateStudentNameEvent updateStudentNameEvent) {
        logger.info("Processing UpdateStudentNameEvent: subscriberAggregateId={}, event={}", subscriberAggregateId, updateStudentNameEvent);
        tournamentSatelliteSatelliteFunctionalities.updateStudentName(subscriberAggregateId, updateStudentNameEvent);
    }

    public void processUpdateTournamentEvent(Integer subscriberAggregateId, UpdateTournamentEvent updateTournamentEvent) {
        logger.info("Processing UpdateTournamentEvent: subscriberAggregateId={}, event={}", subscriberAggregateId, updateTournamentEvent);
        tournamentSatelliteSatelliteFunctionalities.updateTournamentInfo(subscriberAggregateId, updateTournamentEvent);
    }
}
