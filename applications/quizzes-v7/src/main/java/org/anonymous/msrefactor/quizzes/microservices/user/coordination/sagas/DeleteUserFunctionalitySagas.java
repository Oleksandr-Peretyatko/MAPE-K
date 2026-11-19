package org.anonymous.msrefactor.quizzes.microservices.user.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.GenericSagaState;
import org.anonymous.msrefactor.ms.transaction.sagas.messaging.SagaCommand;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.user.DeleteUserCommand;
import org.anonymous.msrefactor.quizzes.commands.user.GetUserByIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.sagas.states.UserSagaState;
import org.anonymous.msrefactor.quizzes.microservices.user.service.UserService;

import java.util.ArrayList;
import java.util.Arrays;

public class DeleteUserFunctionalitySagas extends WorkflowFunctionality {
    private UserDto user;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public DeleteUserFunctionalitySagas(UserService userService, SagaUnitOfWorkService unitOfWorkService,
            Integer userAggregateId, SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(userAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer userAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getUserStep = new SagaStep("getUserStep", () -> {
            GetUserByIdCommand getUserByIdCommand = new GetUserByIdCommand(unitOfWork, ServiceMapping.USER.getServiceName(), userAggregateId);
            SagaCommand sagaCommand = new SagaCommand(getUserByIdCommand);
            sagaCommand.setSemanticLock(UserSagaState.READ_USER);
            UserDto user = (UserDto) commandGateway.send(sagaCommand);
            this.setUser(user);
        });

        getUserStep.registerCompensation(() -> {
            Command command = new Command(unitOfWork, ServiceMapping.USER.getServiceName(), userAggregateId);
            SagaCommand sagaCommand = new SagaCommand(command);
            sagaCommand.setSemanticLock(GenericSagaState.NOT_IN_SAGA);
            commandGateway.send(sagaCommand);
        }, unitOfWork);

        SagaStep deleteUserStep = new SagaStep("deleteUserStep", () -> {
            DeleteUserCommand deleteUserCommand = new DeleteUserCommand(unitOfWork, ServiceMapping.USER.getServiceName(), userAggregateId);
            commandGateway.send(deleteUserCommand);
        }, new ArrayList<>(Arrays.asList(getUserStep)));

        workflow.addStep(getUserStep);
        workflow.addStep(deleteUserStep);
    }

    public UserDto getUser() {
        return user;
    }

    public void setUser(UserDto user) {
        this.user = user;
    }
}
