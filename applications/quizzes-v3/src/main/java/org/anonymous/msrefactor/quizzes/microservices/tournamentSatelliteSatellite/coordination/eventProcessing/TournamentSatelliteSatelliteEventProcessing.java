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
        logger.info("Processing AnonymizeStudentEvent (satellite-satellite): aggregateId={}", aggregateId);
        tournamentSatelliteSatelliteFunctionalities.anonymizeStudent(aggregateId, anonymizeEvent);
    }

    public void processUpdateStudentNameEvent(Integer subscriberAggregateId,
            UpdateStudentNameEvent updateStudentNameEvent) {
        logger.info("Processing UpdateStudentNameEvent (satellite-satellite): subscriberAggregateId={}",
                subscriberAggregateId);
        tournamentSatelliteSatelliteFunctionalities.updateStudentName(subscriberAggregateId, updateStudentNameEvent);
    }

    public void processDisenrollStudentFromCourseExecutionEvent(Integer aggregateId,
            DisenrollStudentFromCourseExecutionEvent event) {
        logger.info("Processing DisenrollStudentFromCourseExecutionEvent (satellite-satellite): aggregateId={}",
                aggregateId);
        tournamentSatelliteSatelliteFunctionalities.disenrollStudent(aggregateId, event);
    }

    public void processAnswerQuestionEvent(Integer aggregateId, QuizAnswerQuestionAnswerEvent event) {
        logger.info("Processing QuizAnswerQuestionAnswerEvent (satellite-satellite): aggregateId={}", aggregateId);
        tournamentSatelliteSatelliteFunctionalities.updateParticipantAnswer(aggregateId, event);
    }
}
