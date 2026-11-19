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
import org.anonymous.msrefactor.quizzes.commands.execution.GetCourseExecutionByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.execution.RemoveStudentFromCourseExecutionCommand;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.sagas.states.CourseExecutionSagaState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RemoveStudentFromCourseExecutionFunctionalitySagas extends WorkflowFunctionality {

    private CourseExecutionDto oldCourseExecution;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public RemoveStudentFromCourseExecutionFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                                              Integer courseExecutionAggregateId, Integer userAggregateId, SagaUnitOfWork unitOfWork,
                                                              CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(courseExecutionAggregateId, userAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer courseExecutionAggregateId, Integer userAggregateId,
                              SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getOldCourseExecutionStep = new SagaStep("getOldCourseExecutionStep", () -> {
            GetCourseExecutionByIdCommand getCourseExecutionByIdCommand = new GetCourseExecutionByIdCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), courseExecutionAggregateId);
            SagaCommand sagaCommand = new SagaCommand(getCourseExecutionByIdCommand);
            sagaCommand.setSemanticLock(CourseExecutionSagaState.READ_COURSE);
            sagaCommand.setForbiddenStates(new ArrayList<>(List.of(CourseExecutionSagaState.READ_COURSE)));
            CourseExecutionDto oldCourseExecution = (CourseExecutionDto) commandGateway.send(sagaCommand);
            this.setOldCourseExecution(oldCourseExecution);
        });

        getOldCourseExecutionStep.registerCompensation(() -> {
            Command command = new Command(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), courseExecutionAggregateId);
            SagaCommand sagaCommand = new SagaCommand(command);
            sagaCommand.setSemanticLock(GenericSagaState.NOT_IN_SAGA);
            commandGateway.send(sagaCommand);
        }, unitOfWork);

        SagaStep removeStudentStep = new SagaStep("removeStudentStep", () -> {
            RemoveStudentFromCourseExecutionCommand removeStudentFromCourseExecutionCommand = new RemoveStudentFromCourseExecutionCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), courseExecutionAggregateId, userAggregateId);
            commandGateway.send(removeStudentFromCourseExecutionCommand);
        }, new ArrayList<>(Arrays.asList(getOldCourseExecutionStep)));

        workflow.addStep(getOldCourseExecutionStep);
        workflow.addStep(removeStudentStep);
    }

    public CourseExecutionDto getOldCourseExecution() {
        return oldCourseExecution;
    }

    public void setOldCourseExecution(CourseExecutionDto oldCourseExecution) {
        this.oldCourseExecution = oldCourseExecution;
    }
}
