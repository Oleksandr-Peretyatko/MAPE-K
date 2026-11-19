package org.anonymous.msrefactor.quizzes.microservices.quiz.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.quiz.RemoveQuizQuestionCommand;

public class RemoveQuizQuestionFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public RemoveQuizQuestionFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
            Integer quizAggregateId, Integer questionAggregateId, SagaUnitOfWork unitOfWork,
            CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        buildWorkflow(quizAggregateId, questionAggregateId, unitOfWork);
    }

    private void buildWorkflow(Integer quizAggregateId, Integer questionAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);
        SagaStep step = new SagaStep("removeQuizQuestion", () -> {
            RemoveQuizQuestionCommand command = new RemoveQuizQuestionCommand(unitOfWork,
                    ServiceMapping.QUIZ.getServiceName(),
                    quizAggregateId, questionAggregateId);
            commandGateway.send(command);
        });
        workflow.addStep(step);
    }
}
