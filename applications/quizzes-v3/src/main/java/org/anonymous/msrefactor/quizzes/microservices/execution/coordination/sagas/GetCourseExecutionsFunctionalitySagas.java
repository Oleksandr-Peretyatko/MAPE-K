package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.execution.GetAllCourseExecutionsCommand;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;

import java.util.List;

public class GetCourseExecutionsFunctionalitySagas extends WorkflowFunctionality {
    private List<CourseExecutionDto> courseExecutions;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public GetCourseExecutionsFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                                 SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(unitOfWork);
    }

    public void buildWorkflow(SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getCourseExecutionsStep = new SagaStep("getCourseExecutionsStep", () -> {
            GetAllCourseExecutionsCommand getAllCourseExecutionsCommand = new GetAllCourseExecutionsCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName());
            List<CourseExecutionDto> courseExecutions = (List<CourseExecutionDto>) commandGateway.send(getAllCourseExecutionsCommand);
            this.setCourseExecutions(courseExecutions);
        });

        workflow.addStep(getCourseExecutionsStep);
    }

    public List<CourseExecutionDto> getCourseExecutions() {
        return courseExecutions;
    }

    public void setCourseExecutions(List<CourseExecutionDto> courseExecutions) {
        this.courseExecutions = courseExecutions;
    }
}