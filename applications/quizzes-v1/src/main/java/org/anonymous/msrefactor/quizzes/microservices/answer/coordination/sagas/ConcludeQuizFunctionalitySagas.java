package org.anonymous.msrefactor.quizzes.microservices.answer.coordination.sagas;

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
import org.anonymous.msrefactor.quizzes.commands.answer.ConcludeQuizCommand;
import org.anonymous.msrefactor.quizzes.commands.answer.GetQuizAnswerDtoByQuizIdAndUserIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.QuizAnswerDto;
import org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.sagas.states.QuizAnswerSagaState;

import java.util.ArrayList;
import java.util.Arrays;

public class ConcludeQuizFunctionalitySagas extends WorkflowFunctionality {

    private QuizAnswerDto quizAnswer;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public ConcludeQuizFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                          Integer quizAggregateId, Integer userAggregateId, SagaUnitOfWork unitOfWork,
                                          CommandGateway CommandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = CommandGateway;
        this.buildWorkflow(quizAggregateId, userAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer quizAggregateId, Integer userAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getQuizAnswerStep = new SagaStep("getQuizAnswerStep", () -> {
            GetQuizAnswerDtoByQuizIdAndUserIdCommand getQuizAnswerDtoByQuizIdAndUserIdCommand = new GetQuizAnswerDtoByQuizIdAndUserIdCommand(unitOfWork, ServiceMapping.ANSWER.getServiceName(), quizAnswer.getAggregateId(), quizAggregateId, userAggregateId);
            SagaCommand sagaCommand = new SagaCommand(getQuizAnswerDtoByQuizIdAndUserIdCommand);
            sagaCommand.setSemanticLock(QuizAnswerSagaState.READ_QUIZ_ANSWER);
            commandGateway.send(sagaCommand);
            this.setQuizAnswer(quizAnswer);
        });

        getQuizAnswerStep.registerCompensation(() -> {
            Command command = new Command(unitOfWork, ServiceMapping.ANSWER.getServiceName(), this.quizAnswer.getAggregateId());
            SagaCommand sagaCommand = new SagaCommand(command);
            sagaCommand.setSemanticLock(GenericSagaState.NOT_IN_SAGA);
            commandGateway.send(sagaCommand);
        }, unitOfWork);

        SagaStep concludeQuizStep = new SagaStep("concludeQuizStep", () -> {
//            quizAnswerService.concludeQuiz(quizAggregateId, userAggregateId, unitOfWork); // TODO
            ConcludeQuizCommand concludeQuizCommand = new ConcludeQuizCommand(unitOfWork, ServiceMapping.ANSWER.getServiceName(), quizAggregateId, userAggregateId);
            commandGateway.send(concludeQuizCommand);
        }, new ArrayList<>(Arrays.asList(getQuizAnswerStep)));

        workflow.addStep(getQuizAnswerStep);
        workflow.addStep(concludeQuizStep);
    }

    public QuizAnswerDto getQuizAnswer() {
        return quizAnswer;
    }

    public void setQuizAnswer(QuizAnswerDto quizAnswer) {
        this.quizAnswer = quizAnswer;
    }
}
