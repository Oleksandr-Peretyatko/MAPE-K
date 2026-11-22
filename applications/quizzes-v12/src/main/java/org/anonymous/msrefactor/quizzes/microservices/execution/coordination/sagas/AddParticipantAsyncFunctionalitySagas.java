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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/*
 * Merge execution <- tournament: the remote async fan-out became direct in-process calls;
 * the future is kept so the step shape (and the tests that drive it) stay unchanged.
 */
public class AddParticipantAsyncFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private final ExecutionService executionService;
    private CompletableFuture<UserDto> userDto;

    public AddParticipantAsyncFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, ExecutionService executionService,
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
            this.userDto = CompletableFuture.completedFuture(
                    executionService.getStudentByExecutionIdAndUserId(executionAggregateId, userAggregateId, unitOfWork));
        });

        SagaStep addParticipantStep = new SagaStep("addParticipantStep", () -> {
            List<SagaAggregate.SagaState> states = new ArrayList<>();
            states.add(CourseExecutionSagaState.IN_UPDATE_TOURNAMENT);

            UserDto user;
            try {
                user = this.userDto.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
            // the dissolved remote send travelled through the command gateway's retry; the
            // direct call keeps that resilience for transient lock conflicts between
            // concurrent workflows mutating the same Execution root
            int attempts = 0;
            while (true) {
                try {
                    unitOfWorkService.verifySagaState(executionAggregateId, states);
                    executionService.addParticipant(tournamentAggregateId, user, unitOfWork);
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

}
