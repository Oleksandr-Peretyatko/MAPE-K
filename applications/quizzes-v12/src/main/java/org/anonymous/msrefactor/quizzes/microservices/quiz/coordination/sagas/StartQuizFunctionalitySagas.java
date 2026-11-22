package org.anonymous.msrefactor.quizzes.microservices.quiz.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.user.GetUserByIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizAnswerDto;
import org.anonymous.msrefactor.quizzes.microservices.quiz.service.QuizService;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.util.ArrayList;
import java.util.Arrays;

// Absorbed from the answer service (merge quiz <- answer). The user read stays remote; the
// former getQuizStep (whose READ_QUIZ marker lock was unchecked and is dropped) and the
// startQuizStep collapsed into one local call — QuizService.startQuiz reads the authoritative
// Quiz root instead of a carried QuizDto.
public class StartQuizFunctionalitySagas extends WorkflowFunctionality {

    private UserDto userDto;
    private QuizAnswerDto quizAnswerDto;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final QuizService quizService;
    private final CommandGateway commandGateway;

    public StartQuizFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, QuizService quizService,
                                       Integer quizAggregateId, Integer courseExecutionAggregateId,
                                       Integer userAggregateId, SagaUnitOfWork unitOfWork,
                                       CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.quizService = quizService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(quizAggregateId, courseExecutionAggregateId, userAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer quizAggregateId, Integer courseExecutionAggregateId,
                              Integer userAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getUserStep = new SagaStep("getUserStep", () -> {
            GetUserByIdCommand getUserByIdCommand = new GetUserByIdCommand(unitOfWork,
                    ServiceMapping.USER.getServiceName(), userAggregateId);
            UserDto userDto = (UserDto) commandGateway.send(getUserByIdCommand);
            this.setUserDto(userDto);
        });

        SagaStep startQuizStep = new SagaStep("startQuizStep", () -> {
            QuizAnswerDto quizAnswerDto = quizService.startQuiz(quizAggregateId, courseExecutionAggregateId,
                    this.getUserDto(), unitOfWork);
            this.setQuizAnswerDto(quizAnswerDto);
        }, new ArrayList<>(Arrays.asList(getUserStep)));

        workflow.addStep(getUserStep);
        workflow.addStep(startQuizStep);
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
