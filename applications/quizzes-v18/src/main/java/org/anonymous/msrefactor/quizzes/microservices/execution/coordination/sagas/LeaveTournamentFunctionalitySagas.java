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

public class LeaveTournamentFunctionalitySagas extends WorkflowFunctionality {
    private TournamentDto oldTournament;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final ExecutionService executionService;

    public LeaveTournamentFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, ExecutionService executionService,
                                             Integer tournamentAggregateId, Integer userAggregateId, SagaUnitOfWork unitOfWork) {
        this.unitOfWorkService = unitOfWorkService;
        this.executionService = executionService;
        this.buildWorkflow(tournamentAggregateId, userAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer tournamentAggregateId, Integer userAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getOldTournamentStep = new SagaStep("getOldTournamentStep", () -> {
            // the semantic lock the remote read took on the tournament is kept,
            // retargeted at the owning execution root; the saga abort restores it
            Integer executionAggregateId = executionService.getExecutionIdOfTournament(tournamentAggregateId);
            unitOfWorkService.registerSagaState(executionAggregateId, CourseExecutionSagaState.READ_TOURNAMENT, unitOfWork);
            this.setOldTournament(executionService.getTournamentById(tournamentAggregateId, unitOfWork));
        });

        SagaStep leaveTournamentStep = new SagaStep("leaveTournamentStep", () -> {
            executionService.leaveTournament(tournamentAggregateId, userAggregateId, unitOfWork);
        }, new ArrayList<>(Arrays.asList(getOldTournamentStep)));

        workflow.addStep(getOldTournamentStep);
        workflow.addStep(leaveTournamentStep);
    }

    public TournamentDto getOldTournament() {
        return oldTournament;
    }

    public void setOldTournament(TournamentDto oldTournament) {
        this.oldTournament = oldTournament;
    }
}
