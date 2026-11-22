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
import org.anonymous.msrefactor.quizzes.events.UpdateTournamentEvent;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.coordination.sagas.*;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.service.TournamentSatelliteSatelliteService;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;
import org.anonymous.msrefactor.quizzes.microservices.user.service.UserService;

@Service
public class TournamentSatelliteSatelliteFunctionalities {
    @Autowired
    private TournamentSatelliteSatelliteService tournamentSatelliteSatelliteService; // testing
    @Autowired(required = false)
    private UserService userService; // testing
    @Autowired
    private SagaUnitOfWorkService sagaUnitOfWorkService;
    @Autowired
    private CommandGateway commandGateway;

    public void addParticipant(Integer tournamentAggregateId, Integer executionAggregateId, Integer userAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        AddParticipantFunctionalitySagas addParticipantFunctionalitySagas = new AddParticipantFunctionalitySagas(
                sagaUnitOfWorkService,
                tournamentAggregateId, executionAggregateId, userAggregateId, sagaUnitOfWork, commandGateway);

        addParticipantFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void addParticipantAsync(Integer tournamentAggregateId, Integer executionAggregateId,
            Integer userAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        AddParticipantAsyncFunctionalitySagas addParticipantAsyncFunctionalitySagas = new AddParticipantAsyncFunctionalitySagas(
                sagaUnitOfWorkService,
                tournamentAggregateId, executionAggregateId, userAggregateId, sagaUnitOfWork, commandGateway);

        addParticipantAsyncFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void leaveTournament(Integer tournamentAggregateId, Integer userAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        LeaveTournamentFunctionalitySagas leaveTournamentFunctionalitySagas = new LeaveTournamentFunctionalitySagas(
                sagaUnitOfWorkService, tournamentAggregateId,
                userAggregateId, sagaUnitOfWork, commandGateway);

        leaveTournamentFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public QuizDto solveQuiz(Integer tournamentAggregateId, Integer userAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        SolveQuizFunctionalitySagas solveQuizFunctionalitySagas = new SolveQuizFunctionalitySagas(
                sagaUnitOfWorkService,
                tournamentAggregateId, userAggregateId, sagaUnitOfWork, commandGateway);

        solveQuizFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return solveQuizFunctionalitySagas.getQuizDto();
    }

    public QuizDto solveQuizAsync(Integer tournamentAggregateId, Integer userAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        SolveQuizAsyncFunctionalitySagas solveQuizAsyncFunctionalitySagas = new SolveQuizAsyncFunctionalitySagas(
                sagaUnitOfWorkService,
                tournamentAggregateId, userAggregateId, sagaUnitOfWork, commandGateway);

        solveQuizAsyncFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return solveQuizAsyncFunctionalitySagas.getQuizDto();
    }

    public UserDto findParticipant(Integer tournamentAggregateId, Integer userAggregateId) throws QuizzesException {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);

        FindParticipantFunctionalitySagas findParticipantFunctionalitySagas = new FindParticipantFunctionalitySagas(
                sagaUnitOfWorkService, tournamentAggregateId, userAggregateId,
                sagaUnitOfWork, commandGateway);

        findParticipantFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return findParticipantFunctionalitySagas.getParticipant();
    }

    /**
     * FOR TESTING PURPOSES
     **/
    public void getTournamentAndUser(Integer tournamentAggregateId, Integer userAggregateId) {
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService
                .createUnitOfWork(new Throwable().getStackTrace()[0].getMethodName());
        tournamentSatelliteSatelliteService.findTournamentSatelliteSatelliteByTournamentId(tournamentAggregateId,
                sagaUnitOfWork);
        userService.getUserById(userAggregateId, sagaUnitOfWork);
    }

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

    public void updateParticipantAnswer(Integer satelliteAggregateId, QuizAnswerQuestionAnswerEvent event) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        UpdateParticipantAnswerFunctionalitySagas functionalitySagas = new UpdateParticipantAnswerFunctionalitySagas(
                sagaUnitOfWorkService, satelliteAggregateId, event.getStudentAggregateId(),
                event.getPublisherAggregateId(), event.getQuestionAggregateId(), event.isCorrect(),
                event.getPublisherAggregateVersion(), sagaUnitOfWork, commandGateway);
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
