package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.execution.GetCourseExecutionsByUserIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;

import java.util.Set;

public class GetCourseExecutionsByUserFunctionalitySagas extends WorkflowFunctionality {
    private Set<CourseExecutionDto> courseExecutions;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public GetCourseExecutionsByUserFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                                       Integer userAggregateId, SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(userAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer userAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getCourseExecutionsByUserStep = new SagaStep("getCourseExecutionsByUserStep", () -> {
            GetCourseExecutionsByUserIdCommand getCourseExecutionsByUserIdCommand = new GetCourseExecutionsByUserIdCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), userAggregateId);
            Set<CourseExecutionDto> courseExecutions = (Set<CourseExecutionDto>) commandGateway.send(getCourseExecutionsByUserIdCommand);
            this.setCourseExecutions(courseExecutions);
        });

        workflow.addStep(getCourseExecutionsByUserStep);
    }

    public Set<CourseExecutionDto> getCourseExecutions() {
        return courseExecutions;
    }

    public void setCourseExecutions(Set<CourseExecutionDto> courseExecutions) {
        this.courseExecutions = courseExecutions;
    }
}