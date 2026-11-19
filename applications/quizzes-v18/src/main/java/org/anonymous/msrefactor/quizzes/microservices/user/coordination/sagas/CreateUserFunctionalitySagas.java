package org.anonymous.msrefactor.quizzes.microservices.user.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.user.CreateUserCommand;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

public class CreateUserFunctionalitySagas extends WorkflowFunctionality {
    private UserDto userDto;
    private UserDto createdUserDto;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public CreateUserFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                        UserDto userDto, SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(userDto, unitOfWork);
    }

    public void buildWorkflow(UserDto userDto, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep createUserStep = new SagaStep("createUserStep", () -> {
            CreateUserCommand createUserCommand = new CreateUserCommand(unitOfWork, ServiceMapping.USER.getServiceName(), userDto);
            createdUserDto = (UserDto) commandGateway.send(createUserCommand);
            setCreatedUserDto(createdUserDto);
        });

        workflow.addStep(createUserStep);
    }

    public UserDto getUserDto() {
        return userDto;
    }

    public void setUserDto(UserDto userDto) {
        this.userDto = userDto;
    }

    public UserDto getCreatedUserDto() {
        return createdUserDto;
    }

    public void setCreatedUserDto(UserDto createdUserDto) {
        this.createdUserDto = createdUserDto;
    }
}