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
import org.anonymous.msrefactor.quizzes.commands.answer.AnswerQuestionCommand;
import org.anonymous.msrefactor.quizzes.commands.answer.GetQuizAnswerDtoByQuizIdAndUserIdCommand;
import org.anonymous.msrefactor.quizzes.commands.course.GetQuestionByIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.QuestionAnswerDto;
import org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.QuizAnswerDto;
import org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.QuizAnswerFactory;
import org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.sagas.states.QuizAnswerSagaState;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionDto;

import java.util.ArrayList;
import java.util.Arrays;

public class AnswerQuestionFunctionalitySagas extends WorkflowFunctionality {

    private QuestionDto questionDto;
    private QuizAnswerDto quizAnswer;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public AnswerQuestionFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, QuizAnswerFactory quizAnswerFactory,
                                            Integer quizAggregateId, Integer userAggregateId, QuestionAnswerDto userQuestionAnswerDto,
                                            SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(quizAggregateId, userAggregateId, userQuestionAnswerDto, quizAnswerFactory, unitOfWork);
    }

    public void buildWorkflow(Integer quizAggregateId, Integer userAggregateId, QuestionAnswerDto userQuestionAnswerDto,
            QuizAnswerFactory quizAnswerFactory, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getQuestionStep = new SagaStep("getQuestionStep", () -> {
            // re-pointed to course (questions merged into the Course aggregate); the
            // READ_QUESTION lock and its release were dropped — Question's states were
            // never checked by any forbidden list or ForSaga query
            GetQuestionByIdCommand getQuestionByIdCommand = new GetQuestionByIdCommand(unitOfWork,
                    ServiceMapping.COURSE.getServiceName(), userQuestionAnswerDto.getQuestionAggregateId());
            QuestionDto questionDto = (QuestionDto) commandGateway.send(getQuestionByIdCommand);
            this.setQuestionDto(questionDto);
        });

        SagaStep getQuizAnswerStep = new SagaStep("getQuizAnswerStep", () -> {
            GetQuizAnswerDtoByQuizIdAndUserIdCommand getQuizAnswerDtoByQuizIdAndUserIdCommand = new GetQuizAnswerDtoByQuizIdAndUserIdCommand(
                    unitOfWork, ServiceMapping.EXECUTION.getServiceName(), quizAnswer.getQuizAggregateId(), quizAggregateId, userAggregateId);
            SagaCommand sagaCommand = new SagaCommand(getQuizAnswerDtoByQuizIdAndUserIdCommand);
            sagaCommand.setSemanticLock(QuizAnswerSagaState.READ_QUIZ_ANSWER);
            QuizAnswerDto quizAnswer = (QuizAnswerDto) commandGateway.send(sagaCommand);
            this.setQuizAnswer(quizAnswer);
        });

        getQuizAnswerStep.registerCompensation(() -> {
            Command command = new Command(unitOfWork, ServiceMapping.EXECUTION.getServiceName(),
                    this.quizAnswer.getAggregateId());
            SagaCommand sagaCommand = new SagaCommand(command);
            sagaCommand.setSemanticLock(GenericSagaState.NOT_IN_SAGA);
            commandGateway.send(sagaCommand);
        }, unitOfWork);

        SagaStep answerQuestionStep = new SagaStep("answerQuestionStep", () -> {
            AnswerQuestionCommand answerQuestion = new AnswerQuestionCommand(unitOfWork,
                    ServiceMapping.EXECUTION.getServiceName(), quizAggregateId, userAggregateId, userQuestionAnswerDto,
                    this.getQuestionDto());
            SagaCommand sagaCommand = new SagaCommand(answerQuestion);
            commandGateway.send(sagaCommand);
        }, new ArrayList<>(Arrays.asList(getQuestionStep, getQuizAnswerStep)));

        workflow.addStep(getQuestionStep);
        workflow.addStep(getQuizAnswerStep);
        workflow.addStep(answerQuestionStep);
    }

    public QuestionDto getQuestionDto() {
        return questionDto;
    }

    public void setQuestionDto(QuestionDto questionDto) {
        this.questionDto = questionDto;
    }

    public QuizAnswerDto getQuizAnswer() {
        return quizAnswer;
    }

    public void setQuizAnswer(QuizAnswerDto quizAnswer) {
        this.quizAnswer = quizAnswer;
    }
}