package org.anonymous.msrefactor.quizzes.microservices.question.coordination.sagas;

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
import org.anonymous.msrefactor.quizzes.commands.course.UpdateCourseQuestionCountCommand;
import org.anonymous.msrefactor.quizzes.commands.question.GetQuestionByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.question.RemoveQuestionCommand;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.sagas.states.QuestionSagaState;

import java.util.ArrayList;
import java.util.Arrays;

public class RemoveQuestionFunctionalitySagas extends WorkflowFunctionality {

    private QuestionDto question;
    private Integer courseAggregateId;
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

        SagaStep getQuestionStep = new SagaStep("getQuestionStep", () -> {
            GetQuestionByIdCommand getQuestionByIdCommand = new GetQuestionByIdCommand(unitOfWork, ServiceMapping.QUESTION.getServiceName(), questionAggregateId);
            SagaCommand sagaCommand = new SagaCommand(getQuestionByIdCommand);
            sagaCommand.setSemanticLock(QuestionSagaState.READ_QUESTION);
            QuestionDto question = (QuestionDto) commandGateway.send(sagaCommand);
            this.setQuestion(question);
            this.courseAggregateId = question.getCourse().getAggregateId();
        });

        getQuestionStep.registerCompensation(() -> {
            Command command = new Command(unitOfWork, ServiceMapping.QUESTION.getServiceName(), questionAggregateId);
            SagaCommand sagaCommand = new SagaCommand(command);
            sagaCommand.setSemanticLock(GenericSagaState.NOT_IN_SAGA);
            commandGateway.send(sagaCommand);
        }, unitOfWork);

        SagaStep removeQuestionStep = new SagaStep("removeQuestionStep", () -> {
            RemoveQuestionCommand removeQuestion = new RemoveQuestionCommand(unitOfWork, ServiceMapping.QUESTION.getServiceName(), questionAggregateId);
            commandGateway.send(removeQuestion);
        }, new ArrayList<>(Arrays.asList(getQuestionStep)));

        // CANNOT_DELETE_LAST_EXECUTION_WITH_CONTENT
        SagaStep updateCourseQuestionCountStep = new SagaStep("updateCourseQuestionCountStep", () -> {
            UpdateCourseQuestionCountCommand cmd = new UpdateCourseQuestionCountCommand(unitOfWork, ServiceMapping.COURSE.getServiceName(), this.courseAggregateId, false);
            commandGateway.send(cmd);
        }, new ArrayList<>(Arrays.asList(getQuestionStep, removeQuestionStep)));

        workflow.addStep(getQuestionStep);
        workflow.addStep(removeQuestionStep);
        workflow.addStep(updateCourseQuestionCountStep);
    }

    public QuestionDto getQuestion() {
        return question;
    }

    public void setQuestion(QuestionDto question) {
        this.question = question;
    }
}
