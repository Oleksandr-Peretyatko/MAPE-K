package org.anonymous.msrefactor.quizzes.microservices.user.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.user.GetStudentsCommand;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.util.List;

public class GetStudentsFunctionalitySagas extends WorkflowFunctionality {
    private List<UserDto> students;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public GetStudentsFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                         SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(unitOfWork);
    }

    public void buildWorkflow(SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getStudentsStep = new SagaStep("getStudentsStep", () -> {
            GetStudentsCommand getStudentsCommand = new GetStudentsCommand(unitOfWork, ServiceMapping.USER.getServiceName());
            List<UserDto> students = (List<UserDto>) commandGateway.send(getStudentsCommand);
            this.setStudents(students);
        });

        workflow.addStep(getStudentsStep);
    }

    public List<UserDto> getStudents() {
        return students;
    }

    public void setStudents(List<UserDto> students) {
        this.students = students;
    }
}