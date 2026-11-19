package org.anonymous.msrefactor.quizzes.microservices.quiz.coordination.sagas;

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
import org.anonymous.msrefactor.quizzes.commands.question.GetQuestionByIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.sagas.states.QuestionSagaState;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuestionAnswerDto;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.sagas.states.QuizSagaState;
import org.anonymous.msrefactor.quizzes.microservices.quiz.service.QuizService;

import java.util.ArrayList;
import java.util.Arrays;

// Absorbed from the answer service (merge quiz <- answer). The question read stays remote; the
// former getQuizAnswerStep and answerQuestionStep collapsed into one local call. QuizAnswer's
// lock states were checked (the old ForSaga repository queries), so the READ_QUIZ_ANSWER lock is
// kept in-process, registered on the owning Quiz root.
public class AnswerQuestionFunctionalitySagas extends WorkflowFunctionality {

    private QuestionDto questionDto;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final QuizService quizService;
    private final CommandGateway commandGateway;

    public AnswerQuestionFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, QuizService quizService,
                                            Integer quizAggregateId, Integer userAggregateId,
                                            QuestionAnswerDto userQuestionAnswerDto,
                                            SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.quizService = quizService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(quizAggregateId, userAggregateId, userQuestionAnswerDto, unitOfWork);
    }

    public void buildWorkflow(Integer quizAggregateId, Integer userAggregateId,
            QuestionAnswerDto userQuestionAnswerDto, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getQuestionStep = new SagaStep("getQuestionStep", () -> {
            GetQuestionByIdCommand getQuestionByIdCommand = new GetQuestionByIdCommand(unitOfWork,
                    ServiceMapping.QUESTION.getServiceName(), userQuestionAnswerDto.getQuestionAggregateId());
            SagaCommand sagaCommand = new SagaCommand(getQuestionByIdCommand);
            sagaCommand.setSemanticLock(QuestionSagaState.READ_QUESTION);
            QuestionDto questionDto = (QuestionDto) commandGateway.send(sagaCommand);
            this.setQuestionDto(questionDto);
        });

        getQuestionStep.registerCompensation(() -> {
            Command command = new Command(unitOfWork, ServiceMapping.QUESTION.getServiceName(),
                    this.questionDto.getAggregateId());
            SagaCommand sagaCommand = new SagaCommand(command);
            sagaCommand.setSemanticLock(GenericSagaState.NOT_IN_SAGA);
            commandGateway.send(sagaCommand);
        }, unitOfWork);

        SagaStep answerQuestionStep = new SagaStep("answerQuestionStep", () -> {
            unitOfWorkService.registerSagaState(quizAggregateId, QuizSagaState.READ_QUIZ_ANSWER, unitOfWork);
            quizService.answerQuestion(quizAggregateId, userAggregateId, userQuestionAnswerDto,
                    this.getQuestionDto(), unitOfWork);
        }, new ArrayList<>(Arrays.asList(getQuestionStep)));

        workflow.addStep(getQuestionStep);
        workflow.addStep(answerQuestionStep);
    }

    public QuestionDto getQuestionDto() {
        return questionDto;
    }

    public void setQuestionDto(QuestionDto questionDto) {
        this.questionDto = questionDto;
    }
}
