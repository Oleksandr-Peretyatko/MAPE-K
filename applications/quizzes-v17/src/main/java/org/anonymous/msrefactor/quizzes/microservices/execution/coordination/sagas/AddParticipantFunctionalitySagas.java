package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.SagaAggregate;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.sagas.states.CourseExecutionSagaState;
import org.anonymous.msrefactor.quizzes.microservices.execution.service.ExecutionService;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AddParticipantFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private final ExecutionService executionService;
    private UserDto userDto;

    public AddParticipantFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, ExecutionService executionService,
                                            Integer tournamentAggregateId, Integer executionAggregateId, Integer userAggregateId,
                                            SagaUnitOfWork unitOfWork) {
        this.unitOfWorkService = unitOfWorkService;
        this.executionService = executionService;
        this.buildWorkflow(tournamentAggregateId, executionAggregateId, userAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer tournamentAggregateId, Integer executionAggregateId, Integer userAggregateId,
            SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getUserStep = new SagaStep("getUserStep", () -> {
            // the student read is in-process now
            this.userDto = executionService.getStudentByExecutionIdAndUserId(executionAggregateId, userAggregateId, unitOfWork);
        });

        SagaStep addParticipantStep = new SagaStep("addParticipantStep", () -> {
            // the forbidden-state check the remote command declared on the tournament is
            // kept, retargeted at the owning execution root (Tournament states moved to
            // CourseExecutionSagaState)
            List<SagaAggregate.SagaState> states = new ArrayList<>();
            states.add(CourseExecutionSagaState.IN_UPDATE_TOURNAMENT);
            unitOfWorkService.verifySagaState(executionAggregateId, states);
            executionService.addParticipant(tournamentAggregateId, this.userDto, unitOfWork);
        }, new ArrayList<>(Arrays.asList(getUserStep)));

        this.workflow.addStep(getUserStep);
        this.workflow.addStep(addParticipantStep);
    }

    public void setUserDto(UserDto userDto) {
        this.userDto = userDto;
    }

    public UserDto getUserDto() {
        return userDto;
    }
}
