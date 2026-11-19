package org.anonymous.msrefactor.quizzes.microservices.tournament.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.tournament.GetTournamentsByCourseExecutionIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto;

import java.util.List;

public class GetTournamentsForCourseExecutionFunctionalitySagas extends WorkflowFunctionality {
    private List<TournamentDto> tournaments;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public GetTournamentsForCourseExecutionFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                                              Integer executionAggregateId, SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(executionAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer executionAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getTournamentsStep = new SagaStep("getTournamentsStep", () -> {
            GetTournamentsByCourseExecutionIdCommand getTournamentsByCourseExecutionIdCommand = new GetTournamentsByCourseExecutionIdCommand(unitOfWork, ServiceMapping.TOURNAMENT.getServiceName(), executionAggregateId);
            List<TournamentDto> tournaments = (List<TournamentDto>) commandGateway.send(getTournamentsByCourseExecutionIdCommand);
            this.setTournaments(tournaments);
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