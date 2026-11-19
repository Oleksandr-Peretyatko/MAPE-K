package org.anonymous.msrefactor.quizzes.microservices.user.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.user.GetUserByIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

public class FindUserByIdFunctionalitySagas extends WorkflowFunctionality {
    private UserDto userDto;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public FindUserByIdFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                          Integer userAggregateId, SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(userAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer userAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep findUserStep = new SagaStep("findUserStep", () -> {
            GetUserByIdCommand getUserByIdCommand = new GetUserByIdCommand(unitOfWork, ServiceMapping.USER.getServiceName(), userAggregateId);
            UserDto userDto = (UserDto) commandGateway.send(getUserByIdCommand);
            this.setUserDto(userDto);
        });

        workflow.addStep(findUserStep);
    }

    public UserDto getUserDto() {
        return userDto;
    }

    public void setUserDto(UserDto userDto) {
        this.userDto = userDto;
    }
}