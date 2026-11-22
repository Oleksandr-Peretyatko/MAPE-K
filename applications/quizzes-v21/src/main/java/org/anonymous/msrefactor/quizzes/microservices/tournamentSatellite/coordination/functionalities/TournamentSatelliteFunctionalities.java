package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.coordination.functionalities;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.quizzes.events.AnonymizeStudentEvent;
import org.anonymous.msrefactor.quizzes.events.DisenrollStudentFromCourseExecutionEvent;
import org.anonymous.msrefactor.quizzes.events.UpdateStudentNameEvent;
import org.anonymous.msrefactor.quizzes.events.UpdateTournamentEvent;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.coordination.sagas.*;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.service.TournamentSatelliteService;

/*
 * The participant-facing use cases (join, leave, solveQuiz, findParticipant)
 * moved to TournamentSatelliteSatelliteFunctionalities with the roster split.
 * What remains is the creator branch of the student-event processing and the
 * refresh of the replicated tournament scalars.
 */
@Service
public class TournamentSatelliteFunctionalities {
    @Autowired
    private TournamentSatelliteService tournamentSatelliteService; // testing
    @Autowired
    private SagaUnitOfWorkService sagaUnitOfWorkService;
    @Autowired
    private CommandGateway commandGateway;

    /******************************* EVENT PROCESSING ********************************/

    public void anonymizeStudent(Integer satelliteAggregateId, AnonymizeStudentEvent event) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        AnonymizeUserTournamentFunctionalitySagas functionalitySagas = new AnonymizeUserTournamentFunctionalitySagas(
                sagaUnitOfWorkService, satelliteAggregateId, event.getPublisherAggregateId(),
                event.getStudentAggregateId(), event.getName(), event.getUsername(),
                event.getPublisherAggregateVersion(), sagaUnitOfWork, commandGateway);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void disenrollStudent(Integer satelliteAggregateId, DisenrollStudentFromCourseExecutionEvent event) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        RemoveUserFunctionalitySagas functionalitySagas = new RemoveUserFunctionalitySagas(
                sagaUnitOfWorkService, satelliteAggregateId, event.getPublisherAggregateId(),
                event.getStudentAggregateId(), event.getPublisherAggregateVersion(), sagaUnitOfWork,
                commandGateway);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void updateStudentName(Integer satelliteAggregateId, UpdateStudentNameEvent event) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        UpdateUserNameFunctionalitySagas functionalitySagas = new UpdateUserNameFunctionalitySagas(
                sagaUnitOfWorkService, event.getPublisherAggregateVersion(), satelliteAggregateId,
                event.getPublisherAggregateId(), event.getStudentAggregateId(), sagaUnitOfWork,
                event.getUpdatedName(), commandGateway);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void updateTournamentInfo(Integer satelliteAggregateId, UpdateTournamentEvent event) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        UpdateTournamentInfoFunctionalitySagas functionalitySagas = new UpdateTournamentInfoFunctionalitySagas(
                sagaUnitOfWorkService, event.getPublisherAggregateId(), event.getStartTime(), event.isCancelled(),
                event.getPublisherAggregateVersion(), sagaUnitOfWork, commandGateway);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }
}
