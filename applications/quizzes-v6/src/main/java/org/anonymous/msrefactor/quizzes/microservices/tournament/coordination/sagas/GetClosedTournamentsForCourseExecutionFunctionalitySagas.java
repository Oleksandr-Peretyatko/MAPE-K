package org.anonymous.msrefactor.quizzes.microservices.tournament.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.tournament.GetClosedTournamentsForCourseExecutionCommand;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto;

import java.util.List;

public class GetClosedTournamentsForCourseExecutionFunctionalitySagas extends WorkflowFunctionality {
    private List<TournamentDto> closedTournaments;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public GetClosedTournamentsForCourseExecutionFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                                                    Integer executionAggregateId, SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(executionAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer executionAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getClosedTournamentsStep = new SagaStep("getClosedTournamentsStep", () -> {
            GetClosedTournamentsForCourseExecutionCommand getClosedTournamentsForCourseExecutionCommand = new GetClosedTournamentsForCourseExecutionCommand(unitOfWork, ServiceMapping.TOURNAMENT.getServiceName(), executionAggregateId);
            List<TournamentDto> closedTournaments = (List<TournamentDto>) commandGateway.send(getClosedTournamentsForCourseExecutionCommand);
            this.setClosedTournaments(closedTournaments);
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