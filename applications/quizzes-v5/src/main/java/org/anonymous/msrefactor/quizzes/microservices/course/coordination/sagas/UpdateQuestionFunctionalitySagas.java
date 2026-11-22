package org.anonymous.msrefactor.quizzes.microservices.course.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.course.GetQuestionByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.course.UpdateQuestionCommand;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionDto;

import java.util.ArrayList;
import java.util.Arrays;

public class UpdateQuestionFunctionalitySagas extends WorkflowFunctionality {
    private QuestionDto question;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public UpdateQuestionFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                            QuestionDto questionDto, SagaUnitOfWork unitOfWork,
                                            CommandGateway CommandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = CommandGateway;
        this.buildWorkflow(questionDto, unitOfWork);
    }

    public void buildWorkflow(QuestionDto questionDto, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        // Question's semantic lock (READ_QUESTION) was unchecked, so it is dropped on dissolution
        // (merge course <- question, layer 3.9); the read is now an in-process load on course.
        SagaStep getQuestionStep = new SagaStep("getQuestionStep", () -> {
            GetQuestionByIdCommand getQuestionByIdCommand = new GetQuestionByIdCommand(unitOfWork, ServiceMapping.COURSE.getServiceName(), questionDto.getAggregateId());
            QuestionDto question = (QuestionDto) commandGateway.send(getQuestionByIdCommand);
            this.setQuestion(question);
        });

        SagaStep updateQuestionStep = new SagaStep("updateQuestionStep", () -> {
            UpdateQuestionCommand updateQuestionCommand = new UpdateQuestionCommand(unitOfWork,
                    ServiceMapping.COURSE.getServiceName(), questionDto);
            commandGateway.send(updateQuestionCommand);
        }, new ArrayList<>(Arrays.asList(getQuestionStep)));

        workflow.addStep(getQuestionStep);
        workflow.addStep(updateQuestionStep);
    }

    public QuestionDto getQuestion() {
        return question;
    }

    public void setQuestion(QuestionDto question) {
        this.question = question;
    }
}
