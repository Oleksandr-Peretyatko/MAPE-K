package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.functionalities;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.quizzes.events.DeleteQuestionEvent;
import org.anonymous.msrefactor.quizzes.events.DeleteTopicEvent;
import org.anonymous.msrefactor.quizzes.events.QuizAnswerQuestionAnswerEvent;
import org.anonymous.msrefactor.quizzes.events.UpdateQuestionEvent;
import org.anonymous.msrefactor.quizzes.events.UpdateTopicEvent;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas.*;
import org.anonymous.msrefactor.quizzes.microservices.execution.service.ExecutionService;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;
import org.anonymous.msrefactor.quizzes.microservices.user.service.UserService;

import java.util.List;
import java.util.Set;

import static org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage.*;

@Service
public class ExecutionFunctionalities {
    @Autowired
    private SagaUnitOfWorkService sagaUnitOfWorkService;

    @Autowired
    private CommandGateway commandGateway;

    @Autowired
    private ExecutionService executionService;

    @Autowired(required = false)
    private UserService userService; // testing

    public CourseExecutionDto createCourseExecution(CourseExecutionDto courseExecutionDto) throws QuizzesException {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        checkInput(courseExecutionDto);

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        CreateCourseExecutionFunctionalitySagas createCourseExecutionFunctionalitySagas = new CreateCourseExecutionFunctionalitySagas(
                sagaUnitOfWorkService, courseExecutionDto, sagaUnitOfWork,
                commandGateway);
        createCourseExecutionFunctionalitySagas.executeWorkflow(sagaUnitOfWork);

        return createCourseExecutionFunctionalitySagas.getCreatedCourseExecution();
    }

    public CourseExecutionDto getCourseExecutionByAggregateId(Integer executionAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        GetCourseExecutionByIdFunctionalitySagas getCourseExecutionByIdFunctionalitySagas = new GetCourseExecutionByIdFunctionalitySagas(
                sagaUnitOfWorkService, executionAggregateId, sagaUnitOfWork,
                commandGateway);
        getCourseExecutionByIdFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return getCourseExecutionByIdFunctionalitySagas.getCourseExecutionDto();
    }

    public List<CourseExecutionDto> getCourseExecutions() {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        GetCourseExecutionsFunctionalitySagas functionality = new GetCourseExecutionsFunctionalitySagas(
                sagaUnitOfWorkService, sagaUnitOfWork, commandGateway);
        functionality.executeWorkflow(sagaUnitOfWork);
        return functionality.getCourseExecutions();
    }

    public void removeCourseExecution(Integer executionAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        RemoveCourseExecutionFunctionalitySagas removeCourseExecutionFunctionalitySagas = new RemoveCourseExecutionFunctionalitySagas(
                sagaUnitOfWorkService, executionAggregateId, sagaUnitOfWork,
                commandGateway);
        removeCourseExecutionFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void addStudent(Integer executionAggregateId, Integer userAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        AddStudentFunctionalitySagas addStudentFunctionalitySagas = new AddStudentFunctionalitySagas(
                sagaUnitOfWorkService, executionAggregateId,
                userAggregateId, sagaUnitOfWork, commandGateway);
        addStudentFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public Set<CourseExecutionDto> getCourseExecutionsByUser(Integer userAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        GetCourseExecutionsByUserFunctionalitySagas getCourseExecutionsByUserFunctionalitySagas = new GetCourseExecutionsByUserFunctionalitySagas(
                sagaUnitOfWorkService, userAggregateId, sagaUnitOfWork, commandGateway);
        getCourseExecutionsByUserFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return getCourseExecutionsByUserFunctionalitySagas.getCourseExecutions();
    }

    public void removeStudentFromCourseExecution(Integer courseExecutionAggregateId, Integer userAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        RemoveStudentFromCourseExecutionFunctionalitySagas removeStudentFromCourseExecutionFunctionalitySagas = new RemoveStudentFromCourseExecutionFunctionalitySagas(
                sagaUnitOfWorkService,
                courseExecutionAggregateId, userAggregateId, sagaUnitOfWork, commandGateway);
        removeStudentFromCourseExecutionFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void anonymizeStudent(Integer executionAggregateId, Integer userAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        AnonymizeStudentFunctionalitySagas anonymizeStudentFunctionalitySagas = new AnonymizeStudentFunctionalitySagas(
                sagaUnitOfWorkService, executionAggregateId,
                userAggregateId, sagaUnitOfWork, commandGateway);
        anonymizeStudentFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void updateStudentName(Integer executionAggregateId, Integer userAggregateId, UserDto userDto) {
        if (userDto.getName() == null) {
            throw new QuizzesException(USER_MISSING_NAME);
        }

        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        UpdateStudentNameFunctionalitySagas updateStudentNameFunctionalitySagas = new UpdateStudentNameFunctionalitySagas(
                sagaUnitOfWorkService, executionAggregateId,
                userAggregateId, userDto, sagaUnitOfWork, commandGateway);
        updateStudentNameFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void removeUserFromCourseExecution(Integer executionAggregateId, Integer userAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        RemoveUserFromCourseExecutionFunctionalitySagas functionalitySagas = new RemoveUserFromCourseExecutionFunctionalitySagas(
                sagaUnitOfWorkService, executionAggregateId, userAggregateId, sagaUnitOfWork, commandGateway);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }


    public QuizDto createQuiz(Integer courseExecutionId, QuizDto quizDto) throws Exception {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        CreateQuizFunctionalitySagas createQuizFunctionalitySagas = new CreateQuizFunctionalitySagas(
                sagaUnitOfWorkService, executionService, courseExecutionId,
                quizDto, sagaUnitOfWork, commandGateway);
        createQuizFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return createQuizFunctionalitySagas.getCreatedQuizDto();
    }

    public QuizDto findQuiz(Integer quizAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        FindQuizFunctionalitySagas findQuizFunctionalitySagas = new FindQuizFunctionalitySagas(
                sagaUnitOfWorkService, executionService, quizAggregateId, sagaUnitOfWork);
        findQuizFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return findQuizFunctionalitySagas.getQuizDto();
    }

    public List<QuizDto> getAvailableQuizzes(Integer userAggregateId, Integer courseExecutionAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        GetAvailableQuizzesFunctionalitySagas getAvailableQuizzesFunctionalitySagas = new GetAvailableQuizzesFunctionalitySagas(
                sagaUnitOfWorkService, executionService, courseExecutionAggregateId, courseExecutionAggregateId,
                sagaUnitOfWork);
        getAvailableQuizzesFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return getAvailableQuizzesFunctionalitySagas.getAvailableQuizzes();
    }

    public QuizDto updateQuiz(QuizDto quizDto) throws Exception {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        UpdateQuizFunctionalitySagas updateQuizFunctionalitySagas = new UpdateQuizFunctionalitySagas(
                sagaUnitOfWorkService, executionService, quizDto, sagaUnitOfWork);
        updateQuizFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return updateQuizFunctionalitySagas.getUpdatedQuizDto();
    }

    public void updateQuestionInQuiz(Integer executionAggregateId, UpdateQuestionEvent event) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        UpdateQuestionInQuizFunctionalitySagas functionalitySagas = new UpdateQuestionInQuizFunctionalitySagas(
                sagaUnitOfWorkService, executionService, executionAggregateId, event.getPublisherAggregateId(),
                event.getTitle(), event.getContent(), event.getPublisherAggregateVersion(), sagaUnitOfWork);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void removeQuizQuestion(Integer executionAggregateId, DeleteQuestionEvent event) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        RemoveQuizQuestionFunctionalitySagas functionalitySagas = new RemoveQuizQuestionFunctionalitySagas(
                sagaUnitOfWorkService, executionService, executionAggregateId, event.getPublisherAggregateId(),
                sagaUnitOfWork);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    /************************************************
     * TOURNAMENT (member entity of the Execution aggregate)
     ************************************************/

    public TournamentDto createTournament(Integer userId, Integer executionId, List<Integer> topicsId,
            TournamentDto tournamentDto) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        checkTournamentInput(userId, topicsId, tournamentDto);

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);

        CreateTournamentFunctionalitySagas createTournamentFunctionalitySagas = new CreateTournamentFunctionalitySagas(
                sagaUnitOfWorkService, executionService,
                userId, executionId, topicsId, tournamentDto, sagaUnitOfWork, commandGateway);

        createTournamentFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return createTournamentFunctionalitySagas.getTournamentDto();
    }

    public TournamentDto createTournamentAsync(Integer userId, Integer executionId, List<Integer> topicsId,
            TournamentDto tournamentDto) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        checkTournamentInput(userId, topicsId, tournamentDto);

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);

        CreateTournamentAsyncFunctionalitySagas createTournamentAsyncFunctionalitySagas = new CreateTournamentAsyncFunctionalitySagas(
                sagaUnitOfWorkService, executionService,
                userId, executionId, topicsId, tournamentDto, sagaUnitOfWork, commandGateway);

        createTournamentAsyncFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return createTournamentAsyncFunctionalitySagas.getTournamentDto();
    }

    public void addParticipant(Integer tournamentAggregateId, Integer executionAggregateId, Integer userAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        AddParticipantFunctionalitySagas addParticipantFunctionalitySagas = new AddParticipantFunctionalitySagas(
                sagaUnitOfWorkService, executionService,
                tournamentAggregateId, executionAggregateId, userAggregateId, sagaUnitOfWork);

        addParticipantFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void addParticipantAsync(Integer tournamentAggregateId, Integer executionAggregateId,
            Integer userAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        AddParticipantAsyncFunctionalitySagas addParticipantAsyncFunctionalitySagas = new AddParticipantAsyncFunctionalitySagas(
                sagaUnitOfWorkService, executionService,
                tournamentAggregateId, executionAggregateId, userAggregateId, sagaUnitOfWork);

        addParticipantAsyncFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void updateTournament(TournamentDto tournamentDto, Set<Integer> topicsAggregateIds) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        UpdateTournamentFunctionalitySagas updateTournamentFunctionalitySagas = new UpdateTournamentFunctionalitySagas(
                sagaUnitOfWorkService, executionService,
                tournamentDto, topicsAggregateIds, sagaUnitOfWork, commandGateway);

        updateTournamentFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public List<TournamentDto> getTournamentsForCourseExecution(Integer executionAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);

        GetTournamentsForCourseExecutionFunctionalitySagas getTournamentsForCourseExecutionFunctionalitySagas = new GetTournamentsForCourseExecutionFunctionalitySagas(
                sagaUnitOfWorkService, executionService, executionAggregateId, sagaUnitOfWork);

        getTournamentsForCourseExecutionFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return getTournamentsForCourseExecutionFunctionalitySagas.getTournaments();
    }

    public List<TournamentDto> getOpenedTournamentsForCourseExecution(Integer executionAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        GetOpenedTournamentsForCourseExecutionFunctionalitySagas getOpenedTournamentsForCourseExecutionFunctionalitySagas = new GetOpenedTournamentsForCourseExecutionFunctionalitySagas(
                sagaUnitOfWorkService, executionService, executionAggregateId, sagaUnitOfWork);

        getOpenedTournamentsForCourseExecutionFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return getOpenedTournamentsForCourseExecutionFunctionalitySagas.getOpenedTournaments();
    }

    public List<TournamentDto> getClosedTournamentsForCourseExecution(Integer executionAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        GetClosedTournamentsForCourseExecutionFunctionalitySagas getClosedTournamentsForCourseExecutionFunctionalitySagas = new GetClosedTournamentsForCourseExecutionFunctionalitySagas(
                sagaUnitOfWorkService, executionService, executionAggregateId, sagaUnitOfWork);

        getClosedTournamentsForCourseExecutionFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return getClosedTournamentsForCourseExecutionFunctionalitySagas.getClosedTournaments();
    }

    public void leaveTournament(Integer tournamentAggregateId, Integer userAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        LeaveTournamentFunctionalitySagas leaveTournamentFunctionalitySagas = new LeaveTournamentFunctionalitySagas(
                sagaUnitOfWorkService, executionService, tournamentAggregateId,
                userAggregateId, sagaUnitOfWork);

        leaveTournamentFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public QuizDto solveQuiz(Integer tournamentAggregateId, Integer userAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        SolveQuizFunctionalitySagas solveQuizFunctionalitySagas = new SolveQuizFunctionalitySagas(
                sagaUnitOfWorkService, executionService,
                tournamentAggregateId, userAggregateId, sagaUnitOfWork, commandGateway);

        solveQuizFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return solveQuizFunctionalitySagas.getQuizDto();
    }

    public QuizDto solveQuizAsync(Integer tournamentAggregateId, Integer userAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        SolveQuizAsyncFunctionalitySagas solveQuizAsyncFunctionalitySagas = new SolveQuizAsyncFunctionalitySagas(
                sagaUnitOfWorkService, executionService,
                tournamentAggregateId, userAggregateId, sagaUnitOfWork, commandGateway);

        solveQuizAsyncFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return solveQuizAsyncFunctionalitySagas.getQuizDto();
    }

    public void cancelTournament(Integer tournamentAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        CancelTournamentFunctionalitySagas cancelTournamentFunctionalitySagas = new CancelTournamentFunctionalitySagas(
                sagaUnitOfWorkService, executionService, tournamentAggregateId,
                sagaUnitOfWork);

        cancelTournamentFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void removeTournament(Integer tournamentAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);

        RemoveTournamentFunctionalitySagas removeTournamentFunctionalitySagas = new RemoveTournamentFunctionalitySagas(
                sagaUnitOfWorkService, executionService, tournamentAggregateId, sagaUnitOfWork);

        removeTournamentFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public TournamentDto findTournament(Integer tournamentAggregateId) throws QuizzesException {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);

        FindTournamentFunctionalitySagas findTournamentFunctionalitySagas = new FindTournamentFunctionalitySagas(
                sagaUnitOfWorkService, executionService, tournamentAggregateId, sagaUnitOfWork);

        findTournamentFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return findTournamentFunctionalitySagas.getTournamentDto();
    }

    public UserDto findParticipant(Integer tournamentAggregateId, Integer userAggregateId) throws QuizzesException {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);

        FindParticipantFunctionalitySagas findParticipantFunctionalitySagas = new FindParticipantFunctionalitySagas(
                sagaUnitOfWorkService, executionService, tournamentAggregateId, userAggregateId,
                sagaUnitOfWork);

        findParticipantFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return findParticipantFunctionalitySagas.getParticipant();
    }

    public void updateTournamentTopic(Integer executionAggregateId, UpdateTopicEvent event) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        UpdateTournamentTopicFunctionalitySagas functionalitySagas = new UpdateTournamentTopicFunctionalitySagas(
                sagaUnitOfWorkService, executionService, executionAggregateId, event.getPublisherAggregateId(),
                event.getTopicName(), event.getPublisherAggregateVersion(), sagaUnitOfWork);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void deleteTournamentTopic(Integer executionAggregateId, DeleteTopicEvent event) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        DeleteTournamentTopicFunctionalitySagas functionalitySagas = new DeleteTournamentTopicFunctionalitySagas(
                sagaUnitOfWorkService, executionService, executionAggregateId, event.getPublisherAggregateId(),
                event.getPublisherAggregateVersion(), sagaUnitOfWork);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void updateParticipantAnswer(Integer executionAggregateId, QuizAnswerQuestionAnswerEvent event) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        UpdateParticipantAnswerFunctionalitySagas functionalitySagas = new UpdateParticipantAnswerFunctionalitySagas(
                sagaUnitOfWorkService, executionService, executionAggregateId, event.getStudentAggregateId(),
                event.getPublisherAggregateId(), event.getQuestionAggregateId(), event.isCorrect(),
                event.getPublisherAggregateVersion(), sagaUnitOfWork);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    /**
     * FOR TESTING PURPOSES
     **/
    public void getTournamentAndUser(Integer tournamentAggregateId, Integer userAggregateId) {
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService
                .createUnitOfWork(new Throwable().getStackTrace()[0].getMethodName());
        executionService.getTournamentById(tournamentAggregateId, sagaUnitOfWork);
        userService.getUserById(userAggregateId, sagaUnitOfWork);
    }

    private void checkTournamentInput(Integer userId, List<Integer> topicsId, TournamentDto tournamentDto) {
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

    private void checkInput(CourseExecutionDto courseExecutionDto) {
        if (courseExecutionDto.getAcronym() == null) {
            throw new QuizzesException(COURSE_EXECUTION_MISSING_ACRONYM);
        }
        if (courseExecutionDto.getAcademicTerm() == null) {
            throw new QuizzesException(COURSE_EXECUTION_MISSING_ACADEMIC_TERM);
        }
        if (courseExecutionDto.getEndDate() == null) {
            throw new QuizzesException(COURSE_EXECUTION_MISSING_END_DATE);
        }

    }

}
