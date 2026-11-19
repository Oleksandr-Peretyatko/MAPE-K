package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

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
import org.anonymous.msrefactor.quizzes.commands.execution.CreateQuizCommand;
import org.anonymous.msrefactor.quizzes.commands.execution.GetCourseExecutionByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.question.GetQuestionByIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.sagas.states.CourseExecutionSagaState;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.sagas.states.QuestionSagaState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public class CreateQuizFunctionalitySagas extends WorkflowFunctionality {
    private CourseExecutionDto courseExecutionDto;
    private Set<QuestionDto> questions;
    private QuizDto createdQuizDto;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public CreateQuizFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                        Integer courseExecutionId, QuizDto quizDto, SagaUnitOfWork unitOfWork, CommandGateway CommandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = CommandGateway;
        this.buildWorkflow(courseExecutionId, quizDto, unitOfWork);
    }

    public void buildWorkflow(Integer courseExecutionId, QuizDto quizDto, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        // Reads and semantic-locks the course execution (READ_COURSE). After the merge the quiz is
        // created as a member of this execution, so the lock is now in-process on <svcA>'s own id
        // (layer 3.4(a) point 3). It replaces the former cross-service getCourseExecutionStep whose
        // only product, the QuizCourseExecution replica, was deleted (layer 1.7).
        SagaStep getCourseExecutionStep = new SagaStep("getCourseExecutionStep", () -> {
            GetCourseExecutionByIdCommand getCourseExecutionByIdCommand = new GetCourseExecutionByIdCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), courseExecutionId);
            SagaCommand sagaCommand = new SagaCommand(getCourseExecutionByIdCommand);
            sagaCommand.setSemanticLock(CourseExecutionSagaState.READ_COURSE);
            this.courseExecutionDto = (CourseExecutionDto) commandGateway.send(sagaCommand);
        });

        getCourseExecutionStep.registerCompensation(() -> {
            Command command = new Command(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), courseExecutionId);
            SagaCommand sagaCommand = new SagaCommand(command);
            sagaCommand.setSemanticLock(GenericSagaState.NOT_IN_SAGA);
            commandGateway.send(sagaCommand);
        }, unitOfWork);

        SagaStep getQuestionsStep = new SagaStep("getQuestionsStep", () -> {
            Set<QuestionDto> questions = quizDto.getQuestionDtos().stream()
                    .map(questionDto -> {
                        GetQuestionByIdCommand getQuestionByIdCommand = new GetQuestionByIdCommand(unitOfWork, ServiceMapping.QUESTION.getServiceName(), questionDto.getAggregateId());
                        SagaCommand sagaCommand = new SagaCommand(getQuestionByIdCommand);
                        sagaCommand.setSemanticLock(QuestionSagaState.READ_QUESTION);
                        return (QuestionDto) commandGateway.send(sagaCommand);
                    })
                    .collect(Collectors.toSet());
            this.setQuestions(questions);
        });

        getQuestionsStep.registerCompensation(() -> {
            quizDto.getQuestionDtos().forEach(questionDto -> {
                Command command = new Command(unitOfWork, ServiceMapping.QUESTION.getServiceName(), questionDto.getAggregateId());
                SagaCommand sagaCommand = new SagaCommand(command);
                sagaCommand.setSemanticLock(GenericSagaState.NOT_IN_SAGA);
                commandGateway.send(sagaCommand);
            });
        }, unitOfWork);

        // Creates the quiz as a member of the (already locked) execution — in-process on <svcA>.
        SagaStep createQuizStep = new SagaStep("createQuizStep", () -> {
            CreateQuizCommand createQuizCommand = new CreateQuizCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), courseExecutionId, this.getQuestions(), quizDto);
            QuizDto createdQuizDto = (QuizDto) commandGateway.send(createQuizCommand);
            this.setCreatedQuizDto(createdQuizDto);
        }, new ArrayList<>(Arrays.asList(getCourseExecutionStep, getQuestionsStep)));

        workflow.addStep(getCourseExecutionStep);
        workflow.addStep(getQuestionsStep);
        workflow.addStep(createQuizStep);
    }

    public CourseExecutionDto getCourseExecutionDto() {
        return courseExecutionDto;
    }

    public void setCourseExecutionDto(CourseExecutionDto courseExecutionDto) {
        this.courseExecutionDto = courseExecutionDto;
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
