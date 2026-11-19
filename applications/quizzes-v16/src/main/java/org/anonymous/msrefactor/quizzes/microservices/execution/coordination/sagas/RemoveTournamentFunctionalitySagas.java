package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.SagaAggregate;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.sagas.states.CourseExecutionSagaState;
import org.anonymous.msrefactor.quizzes.microservices.execution.service.ExecutionService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RemoveTournamentFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private final ExecutionService executionService;

    private TournamentDto tournamentDto;

    public RemoveTournamentFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, ExecutionService executionService,
                                              Integer tournamentAggregateId, SagaUnitOfWork unitOfWork) {
        this.unitOfWorkService = unitOfWorkService;
        this.executionService = executionService;
        this.buildWorkflow(tournamentAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer tournamentAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getTournamentStep = new SagaStep("getTournamentStep", () -> {
            // the semantic lock and forbidden-state check the remote command declared on
            // the tournament are kept, retargeted at the owning execution root
            Integer executionAggregateId = executionService.getExecutionIdOfTournament(tournamentAggregateId);
            List<SagaAggregate.SagaState> states = new ArrayList<>(List.of(CourseExecutionSagaState.IN_UPDATE_TOURNAMENT));
            unitOfWorkService.verifySagaState(executionAggregateId, states);
            unitOfWorkService.registerSagaState(executionAggregateId, CourseExecutionSagaState.IN_DELETE_TOURNAMENT, unitOfWork);
            setTournamentDto(executionService.getTournamentById(tournamentAggregateId, unitOfWork));
        });

        SagaStep removeQuizStep = new SagaStep("removeQuizStep", () -> {
            executionService.removeQuiz(getTournamentDto().getQuiz().getAggregateId(), unitOfWork);
        }, new ArrayList<>(Arrays.asList(getTournamentStep)));

        SagaStep removeTournamentStep = new SagaStep("removeTournamentStep", () -> {
            executionService.removeTournament(tournamentAggregateId, unitOfWork);
        }, new ArrayList<>(Arrays.asList(removeQuizStep)));

        workflow.addStep(getTournamentStep);
        workflow.addStep(removeQuizStep);
        workflow.addStep(removeTournamentStep);
    }

    public TournamentDto getTournamentDto() {
        return tournamentDto;
    }

    public void setTournamentDto(TournamentDto tournamentDto) {
        this.tournamentDto = tournamentDto;
    }
}
