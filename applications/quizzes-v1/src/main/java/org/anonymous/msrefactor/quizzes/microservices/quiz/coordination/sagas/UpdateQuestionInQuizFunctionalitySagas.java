package org.anonymous.msrefactor.quizzes.microservices.quiz.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.quiz.UpdateQuestionCommand;

public class UpdateQuestionInQuizFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public UpdateQuestionInQuizFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
            Integer quizAggregateId, Integer questionAggregateId, String title, String content, Long eventVersion,
            SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        buildWorkflow(quizAggregateId, questionAggregateId, title, content, eventVersion, unitOfWork);
    }

    private void buildWorkflow(Integer quizAggregateId, Integer questionAggregateId, String title, String content,
            Long eventVersion, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);
        SagaStep step = new SagaStep("updateQuestionInQuiz", () -> {
            UpdateQuestionCommand command = new UpdateQuestionCommand(unitOfWork, ServiceMapping.QUIZ.getServiceName(),
                    quizAggregateId, questionAggregateId, title, content, eventVersion);
            commandGateway.send(command);
        });
        workflow.addStep(step);
    }
}
