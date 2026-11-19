package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.execution.GetQuizByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.execution.UpdateQuizCommand;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.QuizQuestion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public class UpdateQuizFunctionalitySagas extends WorkflowFunctionality {
    private QuizDto quiz;
    private QuizDto updatedQuizDto;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public UpdateQuizFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                        QuizDto quizDto, SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(quizDto, unitOfWork);
    }

    public void buildWorkflow(QuizDto quizDto, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getQuizStep = new SagaStep("getQuizStep", () -> {
            GetQuizByIdCommand getQuizByIdCommand = new GetQuizByIdCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), quizDto.getAggregateId());
            QuizDto quiz = (QuizDto) commandGateway.send(getQuizByIdCommand);
            this.setQuiz(quiz);
        });

        SagaStep updateQuizStep = new SagaStep("updateQuizStep", () -> {
            Set<QuizQuestion> quizQuestions = quizDto.getQuestionDtos().stream().map(QuizQuestion::new).collect(Collectors.toSet());
            UpdateQuizCommand updateQuizCommand = new UpdateQuizCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), quizDto, quizQuestions);
            updatedQuizDto = (QuizDto) commandGateway.send(updateQuizCommand);
            this.setUpdatedQuizDto(updatedQuizDto);
        }, new ArrayList<>(Arrays.asList(getQuizStep)));

        workflow.addStep(getQuizStep);
        workflow.addStep(updateQuizStep);
    }

    public QuizDto getQuiz() {
        return quiz;
    }

    public void setQuiz(QuizDto quiz) {
        this.quiz = quiz;
    }

    public QuizDto getUpdatedQuizDto() {
        return updatedQuizDto;
    }

    public void setUpdatedQuizDto(QuizDto updatedQuizDto) {
        this.updatedQuizDto = updatedQuizDto;
    }
}
