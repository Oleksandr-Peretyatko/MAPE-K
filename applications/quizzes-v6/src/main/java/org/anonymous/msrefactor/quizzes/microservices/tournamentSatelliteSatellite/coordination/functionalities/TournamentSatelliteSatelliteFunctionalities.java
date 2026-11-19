package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.coordination.functionalities;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.quizzes.events.AnonymizeStudentEvent;
import org.anonymous.msrefactor.quizzes.events.DisenrollStudentFromCourseExecutionEvent;
import org.anonymous.msrefactor.quizzes.events.QuizAnswerQuestionAnswerEvent;
import org.anonymous.msrefactor.quizzes.events.UpdateStudentNameEvent;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.coordination.sagas.*;

@Service
public class TournamentSatelliteSatelliteFunctionalities {
    @Autowired
    private SagaUnitOfWorkService sagaUnitOfWorkService;
    @Autowired
    private CommandGateway commandGateway;

    public void anonymizeStudent(Integer satelliteAggregateId, AnonymizeStudentEvent event) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        AnonymizeUserTournamentSatelliteSatelliteFunctionalitySagas functionalitySagas = new AnonymizeUserTournamentSatelliteSatelliteFunctionalitySagas(
                sagaUnitOfWorkService, satelliteAggregateId, event.getPublisherAggregateId(),
                event.getStudentAggregateId(), event.getName(), event.getUsername(),
                event.getPublisherAggregateVersion(), sagaUnitOfWork, commandGateway);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void updateStudentName(Integer satelliteAggregateId, UpdateStudentNameEvent event) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        UpdateUserNameTournamentSatelliteSatelliteFunctionalitySagas functionalitySagas = new UpdateUserNameTournamentSatelliteSatelliteFunctionalitySagas(
                sagaUnitOfWorkService, event.getPublisherAggregateVersion(), satelliteAggregateId,
                event.getPublisherAggregateId(), event.getStudentAggregateId(), sagaUnitOfWork,
                event.getUpdatedName(), commandGateway);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void disenrollStudent(Integer satelliteAggregateId, DisenrollStudentFromCourseExecutionEvent event) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        RemoveUserTournamentSatelliteSatelliteFunctionalitySagas functionalitySagas = new RemoveUserTournamentSatelliteSatelliteFunctionalitySagas(
                sagaUnitOfWorkService, satelliteAggregateId, event.getPublisherAggregateId(),
                event.getStudentAggregateId(), event.getPublisherAggregateVersion(), sagaUnitOfWork, commandGateway);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void updateParticipantAnswer(Integer satelliteAggregateId, QuizAnswerQuestionAnswerEvent event) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        UpdateParticipantAnswerTournamentSatelliteSatelliteFunctionalitySagas functionalitySagas = new UpdateParticipantAnswerTournamentSatelliteSatelliteFunctionalitySagas(
                sagaUnitOfWorkService, satelliteAggregateId, event.getStudentAggregateId(),
                event.getPublisherAggregateId(), event.getQuestionAggregateId(), event.isCorrect(),
                event.getPublisherAggregateVersion(), sagaUnitOfWork, commandGateway);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }
}
