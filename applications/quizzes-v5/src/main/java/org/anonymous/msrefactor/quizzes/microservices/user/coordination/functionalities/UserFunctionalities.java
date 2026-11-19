package org.anonymous.msrefactor.quizzes.microservices.user.coordination.functionalities;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;
import org.anonymous.msrefactor.quizzes.microservices.user.coordination.sagas.*;
import org.anonymous.msrefactor.quizzes.microservices.user.service.UserService;

import java.util.List;

@Service
public class UserFunctionalities {
    @Autowired
    private UserService userService;
    @Autowired
    private SagaUnitOfWorkService sagaUnitOfWorkService;

    @Autowired
    private CommandGateway commandGateway;

    public UserDto createUser(UserDto userDto) throws QuizzesException {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        checkInput(userDto);

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);

        CreateUserFunctionalitySagas createUserFunctionalitySagas = new CreateUserFunctionalitySagas(
                sagaUnitOfWorkService, userDto, sagaUnitOfWork, commandGateway);

        createUserFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return createUserFunctionalitySagas.getCreatedUserDto();
    }

    public UserDto findByUserId(Integer userAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        FindUserByIdFunctionalitySagas findUserByIdFunctionalitySagas = new FindUserByIdFunctionalitySagas(
                sagaUnitOfWorkService, userAggregateId, sagaUnitOfWork, commandGateway);

        findUserByIdFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return findUserByIdFunctionalitySagas.getUserDto();
    }

    public void activateUser(Integer userAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        ActivateUserFunctionalitySagas activateUserFunctionalitySagas = new ActivateUserFunctionalitySagas(
                sagaUnitOfWorkService, userAggregateId, sagaUnitOfWork, commandGateway);

        activateUserFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void deactivateUser(Integer userAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        DeactivateUserFunctionalitySagas deactivateUserFunctionalitySagas = new DeactivateUserFunctionalitySagas(
                sagaUnitOfWorkService, userAggregateId, sagaUnitOfWork, commandGateway);

        deactivateUserFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void deleteUser(Integer userAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        DeleteUserFunctionalitySagas deleteUserFunctionalitySagas = new DeleteUserFunctionalitySagas(
                userService, sagaUnitOfWorkService, userAggregateId, sagaUnitOfWork, commandGateway);

        deleteUserFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public List<UserDto> getStudents() {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        GetStudentsFunctionalitySagas getStudentsFunctionalitySagas = new GetStudentsFunctionalitySagas(
                sagaUnitOfWorkService, sagaUnitOfWork, commandGateway);

        getStudentsFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return getStudentsFunctionalitySagas.getStudents();
    }

    public List<UserDto> getTeachers() {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        GetTeachersFunctionalitySagas getTeachersFunctionalitySagas = new GetTeachersFunctionalitySagas(
                sagaUnitOfWorkService, sagaUnitOfWork, commandGateway);

        getTeachersFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return getTeachersFunctionalitySagas.getTeachers();
    }

    private void checkInput(UserDto userDto) {
        if (userDto.getName() == null) {
            throw new QuizzesException(QuizzesErrorMessage.USER_MISSING_NAME);
        }
        if (userDto.getUsername() == null) {
            throw new QuizzesException(QuizzesErrorMessage.USER_MISSING_USERNAME);
        }
        if (userDto.getRole() == null) {
            throw new QuizzesException(QuizzesErrorMessage.USER_MISSING_ROLE);
        }
    }
}
