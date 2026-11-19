package org.anonymous.msrefactor.quizzes.microservices.quiz.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.quiz.GetQuizByIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizDto;

public class FindQuizFunctionalitySagas extends WorkflowFunctionality {
    private QuizDto quizDto;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public FindQuizFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                      Integer quizAggregateId, SagaUnitOfWork unitOfWork, CommandGateway CommandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = CommandGateway;
        this.buildWorkflow(quizAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer quizAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep findQuizStep = new SagaStep("findQuizStep", () -> {
            GetQuizByIdCommand getQuizByIdCommand = new GetQuizByIdCommand(unitOfWork, ServiceMapping.QUIZ.getServiceName(), quizAggregateId);
            QuizDto quizDto = (QuizDto) commandGateway.send(getQuizByIdCommand);
            this.setQuizDto(quizDto);
        });

        workflow.addStep(findQuizStep);
    }

    public QuizDto getQuizDto() {
        return quizDto;
    }

    public void setQuizDto(QuizDto quizDto) {
        this.quizDto = quizDto;
    }
}