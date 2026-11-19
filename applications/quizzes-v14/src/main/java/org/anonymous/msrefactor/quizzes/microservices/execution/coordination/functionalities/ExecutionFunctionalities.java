package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.functionalities;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.quizzes.events.DeleteQuestionEvent;
import org.anonymous.msrefactor.quizzes.events.UpdateQuestionEvent;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas.*;
import org.anonymous.msrefactor.quizzes.microservices.execution.service.ExecutionService;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

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
