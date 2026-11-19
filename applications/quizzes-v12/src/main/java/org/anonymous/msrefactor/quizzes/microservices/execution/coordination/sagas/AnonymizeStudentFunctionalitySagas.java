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
import org.anonymous.msrefactor.quizzes.commands.execution.AnonymizeStudentCommand;
import org.anonymous.msrefactor.quizzes.commands.execution.GetCourseExecutionByIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.sagas.states.CourseExecutionSagaState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

public class AnonymizeStudentFunctionalitySagas extends WorkflowFunctionality {

    private CourseExecutionDto courseExecution;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public AnonymizeStudentFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                              Integer executionAggregateId, Integer userAggregateId, SagaUnitOfWork unitOfWork,
                                              CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(executionAggregateId, userAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer executionAggregateId, Integer userAggregateId,
                              SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getCourseExecutionStep = new SagaStep("getCourseExecutionStep", () -> {
            GetCourseExecutionByIdCommand getCourseExecutionByIdCommand = new GetCourseExecutionByIdCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), executionAggregateId);
            SagaCommand sagaCommand = new SagaCommand(getCourseExecutionByIdCommand);
            sagaCommand.setSemanticLock(CourseExecutionSagaState.READ_COURSE);
            // merge execution <- tournament: anonymisation now also mutates the execution's
            // tournaments synchronously, so it inherits the IN_UPDATE_TOURNAMENT guard the
            // dissolved tournament-side AnonymizeUser workflow used to declare (R4)
            sagaCommand.setForbiddenStates(List.of(CourseExecutionSagaState.READ_COURSE, CourseExecutionSagaState.IN_UPDATE_TOURNAMENT));
            commandGateway.send(sagaCommand);
        });

        getCourseExecutionStep.registerCompensation(() -> {
            Logger.getLogger(AnonymizeStudentFunctionalitySagas.class.getName()).info("Compensating getCourseExecutionStep");
            Command command = new Command(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), executionAggregateId);
            SagaCommand sagaCommand = new SagaCommand(command);
            sagaCommand.setSemanticLock(GenericSagaState.NOT_IN_SAGA);
            commandGateway.send(sagaCommand);
        }, unitOfWork);

        SagaStep anonymizeStudentStep = new SagaStep("anonymizeStudentStep", () -> {
            AnonymizeStudentCommand anonymizeStudentCommand = new AnonymizeStudentCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), executionAggregateId, userAggregateId);
            commandGateway.send(anonymizeStudentCommand);
        }, new ArrayList<>(Arrays.asList(getCourseExecutionStep)));

        workflow.addStep(getCourseExecutionStep);
        workflow.addStep(anonymizeStudentStep);
    }

    public CourseExecutionDto getCourseExecution() {
        return this.courseExecution;
    }

    public void setCourseExecution(CourseExecutionDto courseExecution) {
        this.courseExecution = courseExecution;
    }
}
