package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.messaging.SagaCommand;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.execution.GetExecutionIdByTournamentIdCommand;
import org.anonymous.msrefactor.quizzes.commands.execution.RemoveQuizCommand;
import org.anonymous.msrefactor.quizzes.commands.execution.GetTournamentByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.execution.RemoveTournamentCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatellite.RemoveTournamentSatelliteCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatelliteSatellite.RemoveTournamentSatelliteSatelliteCommand;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.sagas.states.CourseExecutionSagaState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RemoveTournamentFunctionalitySagas extends WorkflowFunctionality {
    private Integer executionAggregateId;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    private TournamentDto tournamentDto;

    public RemoveTournamentFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                              Integer tournamentAggregateId, SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(tournamentAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer tournamentAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getTournamentStep = new SagaStep("getTournamentStep", () -> {
            GetExecutionIdByTournamentIdCommand lookupCommand = new GetExecutionIdByTournamentIdCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), tournamentAggregateId);
            this.executionAggregateId = (Integer) commandGateway.send(lookupCommand);
            GetTournamentByIdCommand getTournamentByIdCommand = new GetTournamentByIdCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), tournamentAggregateId);
            SagaCommand sagaCommand = new SagaCommand(getTournamentByIdCommand);
            // lock and forbidden-state check retargeted to the owning execution root
            sagaCommand.setRootAggregateId(this.executionAggregateId);
            sagaCommand.setForbiddenStates(new ArrayList<>(List.of(CourseExecutionSagaState.IN_UPDATE_TOURNAMENT)));
            sagaCommand.setSemanticLock(CourseExecutionSagaState.IN_DELETE_TOURNAMENT);
            TournamentDto tournamentDto = (TournamentDto) commandGateway.send(sagaCommand);
            setTournamentDto(tournamentDto);
        });

        SagaStep removeQuizStep = new SagaStep("removeQuizStep", () -> {
            RemoveQuizCommand removeQuizCommand = new RemoveQuizCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), getTournamentDto().getQuiz().getAggregateId());
            commandGateway.send(removeQuizCommand);
        }, new ArrayList<>(Arrays.asList(getTournamentStep)));

        // The participant satellite carries invariantDeleteWhenNoParticipants, so it must be the first
        // removal attempted: if the tournament still has participants its remove() throws INVARIANT_BREAK
        // before any other aggregate (core satellite, tournament) is removed, so a rejected removal
        // leaves everything findTournament composes intact.
        SagaStep removeSatelliteSatelliteStep = new SagaStep("removeSatelliteSatelliteStep", () -> {
            RemoveTournamentSatelliteSatelliteCommand removeTournamentSatelliteSatelliteCommand = new RemoveTournamentSatelliteSatelliteCommand(
                    unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE_SATELLITE.getServiceName(), tournamentAggregateId);
            commandGateway.send(removeTournamentSatelliteSatelliteCommand);
        }, new ArrayList<>(Arrays.asList(removeQuizStep)));

        SagaStep removeSatelliteStep = new SagaStep("removeSatelliteStep", () -> {
            RemoveTournamentSatelliteCommand removeTournamentSatelliteCommand = new RemoveTournamentSatelliteCommand(
                    unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE.getServiceName(), tournamentAggregateId);
            commandGateway.send(removeTournamentSatelliteCommand);
        }, new ArrayList<>(Arrays.asList(removeSatelliteSatelliteStep)));

        SagaStep removeTournamentStep = new SagaStep("removeTournamentStep", () -> {
            RemoveTournamentCommand removeTournamentCommand = new RemoveTournamentCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), tournamentAggregateId);
            commandGateway.send(removeTournamentCommand);
        }, new ArrayList<>(Arrays.asList(removeSatelliteStep)));

        workflow.addStep(getTournamentStep);
        workflow.addStep(removeQuizStep);
        workflow.addStep(removeSatelliteStep);
        workflow.addStep(removeSatelliteSatelliteStep);
        workflow.addStep(removeTournamentStep);
    }

    public TournamentDto getTournamentDto() {
        return tournamentDto;
    }

    public void setTournamentDto(TournamentDto tournamentDto) {
        this.tournamentDto = tournamentDto;
    }
}
