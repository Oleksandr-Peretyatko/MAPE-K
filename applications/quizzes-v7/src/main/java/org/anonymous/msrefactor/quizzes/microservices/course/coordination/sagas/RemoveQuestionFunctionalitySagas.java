package org.anonymous.msrefactor.quizzes.microservices.course.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.course.GetQuestionByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.course.RemoveQuestionCommand;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionDto;

import java.util.ArrayList;
import java.util.Arrays;

public class RemoveQuestionFunctionalitySagas extends WorkflowFunctionality {

    private QuestionDto question;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public RemoveQuestionFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                            Integer questionAggregateId, SagaUnitOfWork unitOfWork, CommandGateway CommandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = CommandGateway;
        this.buildWorkflow(questionAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer questionAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        // READ_QUESTION was unchecked, dropped on dissolution (layer 3.9). The count update step is
        // gone too: courseQuestionCount collapsed to a live collection count (layer 1.6) and the
        // removal, now in-process on course, decrements it implicitly.
        SagaStep getQuestionStep = new SagaStep("getQuestionStep", () -> {
            GetQuestionByIdCommand getQuestionByIdCommand = new GetQuestionByIdCommand(unitOfWork, ServiceMapping.COURSE.getServiceName(), questionAggregateId);
            QuestionDto question = (QuestionDto) commandGateway.send(getQuestionByIdCommand);
            this.setQuestion(question);
        });

        SagaStep removeQuestionStep = new SagaStep("removeQuestionStep", () -> {
            RemoveQuestionCommand removeQuestion = new RemoveQuestionCommand(unitOfWork, ServiceMapping.COURSE.getServiceName(), questionAggregateId);
            commandGateway.send(removeQuestion);
        }, new ArrayList<>(Arrays.asList(getQuestionStep)));

        workflow.addStep(getQuestionStep);
        workflow.addStep(removeQuestionStep);
    }

    public QuestionDto getQuestion() {
        return question;
    }

    public void setQuestion(QuestionDto question) {
        this.question = question;
    }
}
