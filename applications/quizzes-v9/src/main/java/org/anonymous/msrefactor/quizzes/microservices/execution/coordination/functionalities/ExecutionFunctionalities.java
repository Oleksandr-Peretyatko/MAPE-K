package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.functionalities;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas.*;
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
