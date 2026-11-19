package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.service.ExecutionService;

import java.util.List;

public class GetTournamentsForCourseExecutionFunctionalitySagas extends WorkflowFunctionality {
    private List<TournamentDto> tournaments;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final ExecutionService executionService;

    public GetTournamentsForCourseExecutionFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, ExecutionService executionService,
                                                              Integer executionAggregateId, SagaUnitOfWork unitOfWork) {
        this.unitOfWorkService = unitOfWorkService;
        this.executionService = executionService;
        this.buildWorkflow(executionAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer executionAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getTournamentsStep = new SagaStep("getTournamentsStep", () -> {
            this.setTournaments(executionService.getTournamentsByCourseExecutionId(executionAggregateId, unitOfWork));
        });

        workflow.addStep(getTournamentsStep);
    }

    public List<TournamentDto> getTournaments() {
        return tournaments;
    }

    public void setTournaments(List<TournamentDto> tournaments) {
        this.tournaments = tournaments;
    }
}
