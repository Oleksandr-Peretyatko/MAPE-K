package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.service.ExecutionService;

import java.util.List;

public class GetClosedTournamentsForCourseExecutionFunctionalitySagas extends WorkflowFunctionality {
    private List<TournamentDto> closedTournaments;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final ExecutionService executionService;

    public GetClosedTournamentsForCourseExecutionFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, ExecutionService executionService,
                                                                    Integer executionAggregateId, SagaUnitOfWork unitOfWork) {
        this.unitOfWorkService = unitOfWorkService;
        this.executionService = executionService;
        this.buildWorkflow(executionAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer executionAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getClosedTournamentsStep = new SagaStep("getClosedTournamentsStep", () -> {
            this.setClosedTournaments(executionService.getClosedTournamentsForCourseExecution(executionAggregateId, unitOfWork));
        });

        workflow.addStep(getClosedTournamentsStep);
    }

    public List<TournamentDto> getClosedTournaments() {
        return closedTournaments;
    }

    public void setClosedTournaments(List<TournamentDto> closedTournaments) {
        this.closedTournaments = closedTournaments;
    }
}
