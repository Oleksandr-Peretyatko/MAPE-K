package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.coordination.functionalities;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.quizzes.events.AnonymizeStudentEvent;
import org.anonymous.msrefactor.quizzes.events.DisenrollStudentFromCourseExecutionEvent;
import org.anonymous.msrefactor.quizzes.events.QuizAnswerQuestionAnswerEvent;
import org.anonymous.msrefactor.quizzes.events.UpdateStudentNameEvent;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.coordination.sagas.*;

@Service
public class TournamentSatelliteFunctionalities {
    @Autowired
    private SagaUnitOfWorkService sagaUnitOfWorkService;
    @Autowired
    private CommandGateway commandGateway;

    public void anonymizeStudent(Integer tournamentAggregateId, AnonymizeStudentEvent event) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        AnonymizeUserTournamentSatelliteFunctionalitySagas functionalitySagas = new AnonymizeUserTournamentSatelliteFunctionalitySagas(
                sagaUnitOfWorkService, tournamentAggregateId, event.getPublisherAggregateId(),
                event.getStudentAggregateId(), event.getName(), event.getUsername(),
                event.getPublisherAggregateVersion(), sagaUnitOfWork, commandGateway);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void updateStudentName(Integer tournamentAggregateId, UpdateStudentNameEvent event) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        UpdateUserNameTournamentSatelliteFunctionalitySagas functionalitySagas = new UpdateUserNameTournamentSatelliteFunctionalitySagas(
                sagaUnitOfWorkService, event.getPublisherAggregateVersion(), tournamentAggregateId,
                event.getPublisherAggregateId(), event.getStudentAggregateId(), sagaUnitOfWork,
                event.getUpdatedName(), commandGateway);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void disenrollStudent(Integer tournamentAggregateId, DisenrollStudentFromCourseExecutionEvent event) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        RemoveUserTournamentSatelliteFunctionalitySagas functionalitySagas = new RemoveUserTournamentSatelliteFunctionalitySagas(
                sagaUnitOfWorkService, tournamentAggregateId, event.getPublisherAggregateId(),
                event.getStudentAggregateId(), event.getPublisherAggregateVersion(), sagaUnitOfWork, commandGateway);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }
}
