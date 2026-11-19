package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.execution.GetAvailableQuizzesCommand;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.QuizDto;

import java.util.List;

public class GetAvailableQuizzesFunctionalitySagas extends WorkflowFunctionality {
    private List<QuizDto> availableQuizzes;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public GetAvailableQuizzesFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                                 Integer userAggregateId, Integer courseExecutionAggregateId, SagaUnitOfWork unitOfWork,
                                                 CommandGateway CommandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = CommandGateway;
        this.buildWorkflow(userAggregateId, courseExecutionAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer userAggregateId, Integer courseExecutionAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getAvailableQuizzesStep = new SagaStep("getAvailableQuizzesStep", () -> {
            GetAvailableQuizzesCommand getAvailableQuizzesCommand = new GetAvailableQuizzesCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), null, courseExecutionAggregateId);
            List<QuizDto> availableQuizzes = (List<QuizDto>) commandGateway.send(getAvailableQuizzesCommand);
            this.setAvailableQuizzes(availableQuizzes);
        });

        workflow.addStep(getAvailableQuizzesStep);
    }

    public List<QuizDto> getAvailableQuizzes() {
        return availableQuizzes;
    }

    public void setAvailableQuizzes(List<QuizDto> availableQuizzes) {
        this.availableQuizzes = availableQuizzes;
    }
}
