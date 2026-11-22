package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.sagas.states.CourseExecutionSagaState;
import org.anonymous.msrefactor.quizzes.microservices.execution.service.ExecutionService;

import java.util.ArrayList;
import java.util.Arrays;

/*
 * Merge execution <- tournament: both steps collapsed to direct ExecutionService calls; the
 * workflow survives to carry the checked READ_TOURNAMENT lock, kept in-process on the owning
 * Execution root and released by the workflow commit (the explicit NOT_IN_SAGA release
 * compensation the remote lock needed is gone).
 */
public class CancelTournamentFunctionalitySagas extends WorkflowFunctionality {

    private TournamentDto tournament;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final ExecutionService executionService;

    public CancelTournamentFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, ExecutionService executionService,
                                              Integer tournamentAggregateId, SagaUnitOfWork unitOfWork) {
        this.unitOfWorkService = unitOfWorkService;
        this.executionService = executionService;
        this.buildWorkflow(tournamentAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer tournamentAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getTournamentStep = new SagaStep("getTournamentStep", () -> {
            Integer executionAggregateId = executionService.findExecutionIdByTournamentId(tournamentAggregateId);
            unitOfWorkService.registerSagaState(executionAggregateId, CourseExecutionSagaState.READ_TOURNAMENT, unitOfWork);
            this.setTournament(executionService.getTournamentById(tournamentAggregateId, unitOfWork));
        });

        SagaStep cancelTournamentStep = new SagaStep("cancelTournamentStep", () -> {
            executionService.cancelTournament(tournamentAggregateId, unitOfWork);
        }, new ArrayList<>(Arrays.asList(getTournamentStep)));

        workflow.addStep(getTournamentStep);
        workflow.addStep(cancelTournamentStep);
    }

    public TournamentDto getTournament() {
        return tournament;
    }

    public void setTournament(TournamentDto Tournament) {
        this.tournament = Tournament;
    }
}
