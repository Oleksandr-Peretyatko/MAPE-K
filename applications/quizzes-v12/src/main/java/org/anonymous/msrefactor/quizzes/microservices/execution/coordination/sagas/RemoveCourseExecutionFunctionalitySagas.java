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
import org.anonymous.msrefactor.quizzes.commands.course.UpdateCourseExecutionCountCommand;
import org.anonymous.msrefactor.quizzes.commands.execution.GetCourseExecutionByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.execution.RemoveCourseExecutionCommand;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.sagas.states.CourseExecutionSagaState;

import java.util.ArrayList;
import java.util.Arrays;

public class RemoveCourseExecutionFunctionalitySagas extends WorkflowFunctionality {
    private CourseExecutionDto courseExecution;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public RemoveCourseExecutionFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                                   Integer executionAggregateId, SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(executionAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer executionAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getCourseExecutionStep = new SagaStep("getCourseExecutionStep", () -> {
            GetCourseExecutionByIdCommand getCourseExecutionCommand = new GetCourseExecutionByIdCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), executionAggregateId);
            SagaCommand sagaCommand = new SagaCommand(getCourseExecutionCommand);
            sagaCommand.setSemanticLock(CourseExecutionSagaState.READ_COURSE);
            CourseExecutionDto courseExecution = (CourseExecutionDto) commandGateway.send(sagaCommand);
            this.setCourseExecution(courseExecution);
        });

        getCourseExecutionStep.registerCompensation(() -> {
            Command command = new Command(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), executionAggregateId);
            SagaCommand sagaCommand = new SagaCommand(command);
            sagaCommand.setSemanticLock(GenericSagaState.NOT_IN_SAGA);
            commandGateway.send(sagaCommand);
        }, unitOfWork);

        SagaStep updateCourseExecutionCountStep = new SagaStep("updateCourseExecutionCountStep", () -> {
            UpdateCourseExecutionCountCommand cmd = new UpdateCourseExecutionCountCommand(unitOfWork, ServiceMapping.COURSE.getServiceName(), this.courseExecution.getCourseAggregateId(), false);
            commandGateway.send(cmd);
        }, new ArrayList<>(Arrays.asList(getCourseExecutionStep)));

        SagaStep removeCourseExecutionStep = new SagaStep("removeCourseExecutionStep", () -> {
            RemoveCourseExecutionCommand removeCourseExecutionCommand = new RemoveCourseExecutionCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), executionAggregateId);
            commandGateway.send(removeCourseExecutionCommand);
        }, new ArrayList<>(Arrays.asList(getCourseExecutionStep, updateCourseExecutionCountStep)));

        workflow.addStep(getCourseExecutionStep);
        workflow.addStep(updateCourseExecutionCountStep);
        workflow.addStep(removeCourseExecutionStep);
    }

    public CourseExecutionDto getCourseExecution() {
        return courseExecution;
    }

    public void setCourseExecution(CourseExecutionDto courseExecution) {
        this.courseExecution = courseExecution;
    }
}
