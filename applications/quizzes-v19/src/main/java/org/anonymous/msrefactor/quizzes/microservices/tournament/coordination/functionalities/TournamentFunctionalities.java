package org.anonymous.msrefactor.quizzes.microservices.tournament.coordination.functionalities;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.quizzes.events.*;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.tournament.coordination.sagas.*;
import org.anonymous.msrefactor.quizzes.microservices.tournament.service.TournamentService;

import java.util.List;
import java.util.Set;

import static org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage.*;

@Service
public class TournamentFunctionalities {
    @Autowired
    private TournamentService tournamentService; // testing
    @Autowired
    private SagaUnitOfWorkService sagaUnitOfWorkService;
    @Autowired
    private CommandGateway commandGateway;

    public void updateTopic(Integer tournamentAggregateId, UpdateTopicEvent event) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        UpdateTopicFunctionalitySagas functionalitySagas = new UpdateTopicFunctionalitySagas(
                sagaUnitOfWorkService, tournamentAggregateId, event.getPublisherAggregateId(),
                event.getTopicName(), event.getPublisherAggregateVersion(), sagaUnitOfWork, commandGateway);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void deleteTopic(Integer tournamentAggregateId, DeleteTopicEvent event) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        DeleteTopicFunctionalitySagas functionalitySagas = new DeleteTopicFunctionalitySagas(
                sagaUnitOfWorkService, tournamentAggregateId, event.getPublisherAggregateId(),
                event.getPublisherAggregateVersion(), sagaUnitOfWork, commandGateway);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void invalidateQuiz(Integer tournamentAggregateId, InvalidateQuizEvent event) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        InvalidateQuizFunctionalitySagas functionalitySagas = new InvalidateQuizFunctionalitySagas(
                sagaUnitOfWorkService, tournamentAggregateId, event.getPublisherAggregateId(),
                event.getPublisherAggregateVersion(), sagaUnitOfWork, commandGateway);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public TournamentDto createTournament(Integer userId, Integer executionId, List<Integer> topicsId,
            TournamentDto tournamentDto) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        checkInput(userId, topicsId, tournamentDto);

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);

        CreateTournamentFunctionalitySagas createTournamentFunctionalitySagas = new CreateTournamentFunctionalitySagas(
                sagaUnitOfWorkService,
                userId, executionId, topicsId, tournamentDto, sagaUnitOfWork, commandGateway);

        createTournamentFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return createTournamentFunctionalitySagas.getTournamentDto();
    }

    public TournamentDto createTournamentAsync(Integer userId, Integer executionId, List<Integer> topicsId,
            TournamentDto tournamentDto) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        checkInput(userId, topicsId, tournamentDto);

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);

        CreateTournamentAsyncFunctionalitySagas createTournamentAsyncFunctionalitySagas = new CreateTournamentAsyncFunctionalitySagas(
                sagaUnitOfWorkService,
                userId, executionId, topicsId, tournamentDto, sagaUnitOfWork, commandGateway);

        createTournamentAsyncFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return createTournamentAsyncFunctionalitySagas.getTournamentDto();
    }

    public void updateTournament(TournamentDto tournamentDto, Set<Integer> topicsAggregateIds) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        UpdateTournamentFunctionalitySagas updateTournamentFunctionalitySagas = new UpdateTournamentFunctionalitySagas(
                sagaUnitOfWorkService,
                tournamentDto, topicsAggregateIds, sagaUnitOfWork, commandGateway);

        updateTournamentFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public List<TournamentDto> getTournamentsForCourseExecution(Integer executionAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);

        GetTournamentsForCourseExecutionFunctionalitySagas getTournamentsForCourseExecutionFunctionalitySagas = new GetTournamentsForCourseExecutionFunctionalitySagas(
                sagaUnitOfWorkService, executionAggregateId, sagaUnitOfWork, commandGateway);

        getTournamentsForCourseExecutionFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return getTournamentsForCourseExecutionFunctionalitySagas.getTournaments();
    }

    public List<TournamentDto> getOpenedTournamentsForCourseExecution(Integer executionAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        GetOpenedTournamentsForCourseExecutionFunctionalitySagas getOpenedTournamentsForCourseExecutionFunctionalitySagas = new GetOpenedTournamentsForCourseExecutionFunctionalitySagas(
                sagaUnitOfWorkService, executionAggregateId, sagaUnitOfWork, commandGateway);

        getOpenedTournamentsForCourseExecutionFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return getOpenedTournamentsForCourseExecutionFunctionalitySagas.getOpenedTournaments();
    }

    public List<TournamentDto> getClosedTournamentsForCourseExecution(Integer executionAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        GetClosedTournamentsForCourseExecutionFunctionalitySagas getClosedTournamentsForCourseExecutionFunctionalitySagas = new GetClosedTournamentsForCourseExecutionFunctionalitySagas(
                sagaUnitOfWorkService, executionAggregateId, sagaUnitOfWork, commandGateway);

        getClosedTournamentsForCourseExecutionFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return getClosedTournamentsForCourseExecutionFunctionalitySagas.getClosedTournaments();
    }

    public void removeCourseExecution(Integer tournamentAggregateId,
            DeleteCourseExecutionEvent deleteCourseExecutionEvent) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        RemoveCourseExecutionFunctionalitySagas functionalitySagas = new RemoveCourseExecutionFunctionalitySagas(
                sagaUnitOfWorkService, tournamentAggregateId,
                deleteCourseExecutionEvent.getPublisherAggregateId(),
                deleteCourseExecutionEvent.getPublisherAggregateVersion(), sagaUnitOfWork, commandGateway);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void cancelTournament(Integer tournamentAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        CancelTournamentFunctionalitySagas cancelTournamentFunctionalitySagas = new CancelTournamentFunctionalitySagas(
                sagaUnitOfWorkService, tournamentAggregateId,
                sagaUnitOfWork, commandGateway);

        cancelTournamentFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void removeTournament(Integer tournamentAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);

        RemoveTournamentFunctionalitySagas removeTournamentFunctionalitySagas = new RemoveTournamentFunctionalitySagas(
                sagaUnitOfWorkService, tournamentAggregateId, sagaUnitOfWork,
                commandGateway);

        removeTournamentFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public TournamentDto findTournament(Integer tournamentAggregateId) throws QuizzesException {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);

        FindTournamentFunctionalitySagas findTournamentFunctionalitySagas = new FindTournamentFunctionalitySagas(
                sagaUnitOfWorkService, tournamentAggregateId, sagaUnitOfWork,
                commandGateway);

        findTournamentFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return findTournamentFunctionalitySagas.getTournamentDto();
    }

    /**
     * FOR TESTING PURPOSES
     **/
    private void checkInput(Integer userId, List<Integer> topicsId, TournamentDto tournamentDto) {
        if (userId == null) {
            throw new QuizzesException(TOURNAMENT_MISSING_USER);
        }
        if (topicsId == null) {
            throw new QuizzesException(TOURNAMENT_MISSING_TOPICS);
        }
        if (tournamentDto.getStartTime() == null) {
            throw new QuizzesException(TOURNAMENT_MISSING_START_TIME);
        }
        if (tournamentDto.getEndTime() == null) {
            throw new QuizzesException(TOURNAMENT_MISSING_END_TIME);
        }
        if (tournamentDto.getNumberOfQuestions() == null) {
            throw new QuizzesException(TOURNAMENT_MISSING_NUMBER_OF_QUESTIONS);
        }
    }

}
