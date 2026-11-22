package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

import org.springframework.dao.TransientDataAccessException;
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

/*
 * Merge execution <- tournament: both steps were remote sends and are now direct
 * ExecutionService calls in one unit of work. Tournament's lock states are checked, so the
 * IN_UPDATE_TOURNAMENT forbidden-state guard survives in-process, checked against the
 * owning Execution root.
 */
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
            this.userDto = executionService.getStudentByExecutionIdAndUserId(executionAggregateId, userAggregateId, unitOfWork);
        });

        SagaStep addParticipantStep = new SagaStep("addParticipantStep", () -> {
            List<SagaAggregate.SagaState> states = new ArrayList<>();
            states.add(CourseExecutionSagaState.IN_UPDATE_TOURNAMENT);
            // the dissolved remote send travelled through the command gateway's retry; the
            // direct call keeps that resilience for transient lock conflicts between
            // concurrent workflows mutating the same Execution root
            int attempts = 0;
            while (true) {
                try {
                    unitOfWorkService.verifySagaState(executionAggregateId, states);
                    executionService.addParticipant(tournamentAggregateId, this.userDto, unitOfWork);
                    break;
                } catch (TransientDataAccessException e) {
                    if (++attempts >= 3) {
                        throw e;
                    }
                }
            }
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
