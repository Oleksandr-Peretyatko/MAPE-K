package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.course.GetQuestionByIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.sagas.states.CourseExecutionSagaState;
import org.anonymous.msrefactor.quizzes.microservices.execution.service.ExecutionService;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionDto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public class CreateQuizFunctionalitySagas extends WorkflowFunctionality {
    private Set<QuestionDto> questions;
    private QuizDto createdQuizDto;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final ExecutionService executionService;
    private final CommandGateway commandGateway;

    public CreateQuizFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, ExecutionService executionService,
                                        Integer courseExecutionId, QuizDto quizDto, SagaUnitOfWork unitOfWork, CommandGateway CommandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.executionService = executionService;
        this.commandGateway = CommandGateway;
        this.buildWorkflow(courseExecutionId, quizDto, unitOfWork);
    }

    public void buildWorkflow(Integer courseExecutionId, QuizDto quizDto, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getQuestionsStep = new SagaStep("getQuestionsStep", () -> { // TODO
            // re-pointed to course (questions merged into the Course aggregate); the
            // READ_QUESTION lock and its release were dropped — Question's states were
            // never checked by any forbidden list or ForSaga query
            Set<QuestionDto> questions = quizDto.getQuestionDtos().stream()
                    .map(questionDto -> {
                        GetQuestionByIdCommand getQuestionByIdCommand = new GetQuestionByIdCommand(unitOfWork, ServiceMapping.COURSE.getServiceName(), questionDto.getAggregateId());
                        return (QuestionDto) commandGateway.send(getQuestionByIdCommand);
                    })
                    .collect(Collectors.toSet());
            this.setQuestions(questions);
        });

        SagaStep createQuizStep = new SagaStep("createQuizStep", () -> {
            // the execution read and the quiz creation are local now; the semantic lock the
            // remote read took on the execution is kept in-process (Execution states are checked)
            unitOfWorkService.registerSagaState(courseExecutionId, CourseExecutionSagaState.READ_COURSE, unitOfWork);
            QuizDto createdQuizDto = executionService.createQuiz(courseExecutionId, this.getQuestions(), quizDto, unitOfWork);
            this.setCreatedQuizDto(createdQuizDto);
        }, new ArrayList<>(Arrays.asList(getQuestionsStep)));

        workflow.addStep(getQuestionsStep);
        workflow.addStep(createQuizStep);
    }

    public Set<QuestionDto> getQuestions() {
        return questions;
    }

    public void setQuestions(Set<QuestionDto> questions) {
        this.questions = questions;
    }

    public QuizDto getCreatedQuizDto() {
        return createdQuizDto;
    }

    public void setCreatedQuizDto(QuizDto createdQuizDto) {
        this.createdQuizDto = createdQuizDto;
    }
}
