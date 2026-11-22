package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.GenericSagaState;
import org.anonymous.msrefactor.ms.transaction.sagas.messaging.SagaCommand;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.execution.GetExecutionIdByTournamentIdCommand;
import org.anonymous.msrefactor.quizzes.commands.execution.GetTournamentByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatelliteSatellite.LeaveTournamentCommand;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.sagas.states.CourseExecutionSagaState;

import java.util.ArrayList;
import java.util.Arrays;

public class LeaveTournamentFunctionalitySagas extends WorkflowFunctionality {
    private TournamentDto oldTournament;
    private Integer executionAggregateId;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public LeaveTournamentFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                             Integer tournamentAggregateId, Integer userAggregateId, SagaUnitOfWork unitOfWork,
                                             CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(tournamentAggregateId, userAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer tournamentAggregateId, Integer userAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getOldTournamentStep = new SagaStep("getOldTournamentStep", () -> {
            GetExecutionIdByTournamentIdCommand lookupCommand = new GetExecutionIdByTournamentIdCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), tournamentAggregateId);
            this.executionAggregateId = (Integer) commandGateway.send(lookupCommand);
            GetTournamentByIdCommand getTournamentByIdCommand = new GetTournamentByIdCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), tournamentAggregateId);
            SagaCommand sagaCommand = new SagaCommand(getTournamentByIdCommand);
            // lock retargeted to the owning execution root (the tournament is a member now)
            sagaCommand.setRootAggregateId(this.executionAggregateId);
            sagaCommand.setSemanticLock(CourseExecutionSagaState.READ_TOURNAMENT);
            TournamentDto oldTournament = (TournamentDto) commandGateway.send(sagaCommand);
            this.setOldTournament(oldTournament);
        });

        getOldTournamentStep.registerCompensation(() -> {
            Command command = new Command(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), this.executionAggregateId);
            SagaCommand sagaCommand = new SagaCommand(command);
            sagaCommand.setSemanticLock(GenericSagaState.NOT_IN_SAGA);
            commandGateway.send(sagaCommand);
        }, unitOfWork);

        SagaStep leaveTournamentStep = new SagaStep("leaveTournamentStep", () -> {
            LeaveTournamentCommand leaveTournamentCommand = new LeaveTournamentCommand(unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE_SATELLITE.getServiceName(), tournamentAggregateId, userAggregateId);
            commandGateway.send(leaveTournamentCommand);
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
