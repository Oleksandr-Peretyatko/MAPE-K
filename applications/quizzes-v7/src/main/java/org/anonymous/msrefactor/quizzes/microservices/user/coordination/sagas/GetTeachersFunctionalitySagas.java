package org.anonymous.msrefactor.quizzes.microservices.user.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.user.GetTeachersCommand;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.util.List;

public class GetTeachersFunctionalitySagas extends WorkflowFunctionality {
    private List<UserDto> teachers;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway CommandGateway;

    public GetTeachersFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                         SagaUnitOfWork unitOfWork, CommandGateway CommandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.CommandGateway = CommandGateway;
        this.buildWorkflow(unitOfWork);
    }

    public void buildWorkflow(SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getTeachersStep = new SagaStep("getTeachersStep", () -> {
            GetTeachersCommand getTeachersCommand = new GetTeachersCommand(unitOfWork, ServiceMapping.USER.getServiceName());
            List<UserDto> teachers = (List<UserDto>) CommandGateway.send(getTeachersCommand);
            this.setTeachers(teachers);
        });

        workflow.addStep(getTeachersStep);
    }

    public List<UserDto> getTeachers() {
        return teachers;
    }

    public void setTeachers(List<UserDto> teachers) {
        this.teachers = teachers;
    }
}