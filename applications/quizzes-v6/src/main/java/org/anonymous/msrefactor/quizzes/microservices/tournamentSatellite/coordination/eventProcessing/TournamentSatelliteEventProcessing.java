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
        logger.info("Processing AnonymizeStudentEvent (satellite): aggregateId={}", aggregateId);
        tournamentSatelliteFunctionalities.anonymizeStudent(aggregateId, anonymizeEvent);
    }

    public void processUpdateStudentNameEvent(Integer subscriberAggregateId, UpdateStudentNameEvent updateStudentNameEvent) {
        logger.info("Processing UpdateStudentNameEvent (satellite): subscriberAggregateId={}", subscriberAggregateId);
        tournamentSatelliteFunctionalities.updateStudentName(subscriberAggregateId, updateStudentNameEvent);
    }

    public void processDisenrollStudentFromCourseExecutionEvent(Integer aggregateId, DisenrollStudentFromCourseExecutionEvent event) {
        logger.info("Processing DisenrollStudentFromCourseExecutionEvent (satellite): aggregateId={}", aggregateId);
        tournamentSatelliteFunctionalities.disenrollStudent(aggregateId, event);
    }
}
