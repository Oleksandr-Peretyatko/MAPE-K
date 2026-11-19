package org.anonymous.msrefactor.quizzes.microservices.user.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.Step;
import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.user.DeactivateUserCommand;
import org.anonymous.msrefactor.quizzes.commands.user.GetUserByIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.util.ArrayList;
import java.util.Arrays;

public class DeactivateUserFunctionalitySagas extends WorkflowFunctionality {
    private UserDto user;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public DeactivateUserFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                            Integer userAggregateId, SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(userAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer userAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getUserStep = new SagaStep("getUserStep", () -> {
            GetUserByIdCommand getUserByIdCommand = new GetUserByIdCommand(unitOfWork, ServiceMapping.USER.getServiceName(), userAggregateId);
            UserDto user = (UserDto) commandGateway.send(getUserByIdCommand);
            this.setUser(user);
        });

        Step deactivateUserStep = new Step("deactivateUserStep", () -> {
            DeactivateUserCommand deactivateUserCommand = new DeactivateUserCommand(unitOfWork, ServiceMapping.USER.getServiceName(), userAggregateId);
            commandGateway.send(deactivateUserCommand);
        }, new ArrayList<>(Arrays.asList(getUserStep)));

        workflow.addStep(getUserStep);
        workflow.addStep(deactivateUserStep);
    }

        

    public UserDto getUser() {
        return user;
    }

    public void setUser(UserDto user) {
        this.user = user;
    }
}