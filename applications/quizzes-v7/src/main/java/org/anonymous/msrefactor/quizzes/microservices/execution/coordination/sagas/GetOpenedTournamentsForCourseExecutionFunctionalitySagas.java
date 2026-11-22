package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.execution.GetOpenedTournamentsForCourseExecutionCommand;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.TournamentDto;

import java.util.List;

public class GetOpenedTournamentsForCourseExecutionFunctionalitySagas extends WorkflowFunctionality {
    private List<TournamentDto> openedTournaments;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public GetOpenedTournamentsForCourseExecutionFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                                                    Integer executionAggregateId, SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(executionAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer executionAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getOpenedTournamentsStep = new SagaStep("getOpenedTournamentsStep", () -> {
            GetOpenedTournamentsForCourseExecutionCommand getOpenedTournamentsForCourseExecutionCommand = new GetOpenedTournamentsForCourseExecutionCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), executionAggregateId);
            List<TournamentDto> openedTournaments = (List<TournamentDto>) commandGateway.send(getOpenedTournamentsForCourseExecutionCommand);
            this.setOpenedTournaments(openedTournaments);
        });

        workflow.addStep(getOpenedTournamentsStep);
    }

    public List<TournamentDto> getOpenedTournaments() {
        return openedTournaments;
    }

    public void setOpenedTournaments(List<TournamentDto> openedTournaments) {
        this.openedTournaments = openedTournaments;
    }
}
