package org.anonymous.msrefactor.quizzes.microservices.answer.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.messaging.SagaCommand;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.answer.StartQuizCommand;
import org.anonymous.msrefactor.quizzes.commands.quiz.GetQuizByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.user.GetUserByIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.QuizAnswerDto;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.sagas.states.QuizSagaState;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.util.ArrayList;
import java.util.Arrays;

public class StartQuizFunctionalitySagas extends WorkflowFunctionality {

    private QuizDto quizDto;
    private UserDto userDto;
    private QuizAnswerDto quizAnswerDto;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public StartQuizFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                       Integer quizAggregateId, Integer courseExecutionAggregateId,
                                       Integer userAggregateId, SagaUnitOfWork unitOfWork,
                                       CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(quizAggregateId, courseExecutionAggregateId, userAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer quizAggregateId, Integer courseExecutionAggregateId,
                              Integer userAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getQuizStep = new SagaStep("getQuizStep", () -> {
            GetQuizByIdCommand getQuizByIdCommand = new GetQuizByIdCommand(unitOfWork, ServiceMapping.QUIZ.getServiceName(), quizAggregateId);
            SagaCommand sagaCommand = new SagaCommand(getQuizByIdCommand);
            sagaCommand.setSemanticLock(QuizSagaState.READ_QUIZ);
            QuizDto quizDto = (QuizDto) commandGateway.send(sagaCommand);
            this.setQuizDto(quizDto);
        });

        SagaStep getUserStep = new SagaStep("getUserStep", () -> {
            GetUserByIdCommand getUserByIdCommand = new GetUserByIdCommand(unitOfWork,
                    ServiceMapping.USER.getServiceName(), userAggregateId);
            UserDto userDto = (UserDto) commandGateway.send(getUserByIdCommand);
            this.setUserDto(userDto);
        });

        SagaStep startQuizStep = new SagaStep("startQuizStep", () -> {
            StartQuizCommand startQuizCommand = new StartQuizCommand(unitOfWork,
                    ServiceMapping.ANSWER.getServiceName(), quizAggregateId, courseExecutionAggregateId,
                    this.getQuizDto(), this.getUserDto());
            QuizAnswerDto quizAnswerDto = (QuizAnswerDto) commandGateway.send(startQuizCommand);
            this.setQuizAnswerDto(quizAnswerDto);
        }, new ArrayList<>(Arrays.asList(getQuizStep, getUserStep)));

        workflow.addStep(getQuizStep);
        workflow.addStep(getUserStep);
        workflow.addStep(startQuizStep);
    }

    public QuizDto getQuizDto() {
        return quizDto;
    }

    public void setQuizDto(QuizDto quizDto) {
        this.quizDto = quizDto;
    }

    public UserDto getUserDto() {
        return userDto;
    }

    public void setUserDto(UserDto userDto) {
        this.userDto = userDto;
    }

    public QuizAnswerDto getQuizAnswerDto() {
        return quizAnswerDto;
    }

    public void setQuizAnswerDto(QuizAnswerDto quizAnswerDto) {
        this.quizAnswerDto = quizAnswerDto;
    }
}
