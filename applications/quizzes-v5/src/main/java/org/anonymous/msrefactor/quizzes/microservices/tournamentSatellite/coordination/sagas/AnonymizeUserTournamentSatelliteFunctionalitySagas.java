package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.SagaAggregate;
import org.anonymous.msrefactor.ms.transaction.sagas.messaging.SagaCommand;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.tournament.GetTournamentByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatellite.AnonymizeUserCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatellite.GetTournamentSatelliteByIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.sagas.states.TournamentSagaState;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatelliteDto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AnonymizeUserTournamentSatelliteFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;
    private TournamentSatelliteDto satelliteDto;

    public AnonymizeUserTournamentSatelliteFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
            Integer satelliteAggregateId, Integer executionAggregateId, Integer userAggregateId, String name,
            String username, Long eventVersion, SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(satelliteAggregateId, executionAggregateId, userAggregateId, name, username, eventVersion,
                unitOfWork);
    }

    public void buildWorkflow(Integer satelliteAggregateId, Integer executionAggregateId, Integer userAggregateId,
            String name, String username, Long eventVersion, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getSatelliteStep = new SagaStep("getSatelliteStep", () -> {
            GetTournamentSatelliteByIdCommand command = new GetTournamentSatelliteByIdCommand(unitOfWork,
                    ServiceMapping.TOURNAMENT_SATELLITE.getServiceName(), satelliteAggregateId);
            this.satelliteDto = (TournamentSatelliteDto) commandGateway.send(command);
        });

        // Verifies the core is not locked in an update before anything is anonymized (preserves the
        // pre-split forbidden-state check that the single AnonymizeUserCommand used to perform).
        SagaStep anonymizeCoreStep = new SagaStep("anonymizeCoreStep", () -> {
            if (this.satelliteDto != null && this.satelliteDto.getTournamentAggregateId() != null) {
                List<SagaAggregate.SagaState> states = new ArrayList<>();
                states.add(TournamentSagaState.IN_UPDATE_TOURNAMENT);
                org.anonymous.msrefactor.quizzes.commands.tournament.AnonymizeUserCommand coreCommand =
                        new org.anonymous.msrefactor.quizzes.commands.tournament.AnonymizeUserCommand(unitOfWork,
                                ServiceMapping.TOURNAMENT.getServiceName(),
                                this.satelliteDto.getTournamentAggregateId(), executionAggregateId, userAggregateId,
                                name, username, eventVersion);
                SagaCommand sagaCommand = new SagaCommand(coreCommand);
                sagaCommand.setForbiddenStates(states);
                commandGateway.send(sagaCommand);
            }
        }, new ArrayList<>(Arrays.asList(getSatelliteStep)));

        SagaStep anonymizeSatelliteStep = new SagaStep("anonymizeSatelliteStep", () -> {
            AnonymizeUserCommand anonymizeUserCommand = new AnonymizeUserCommand(unitOfWork,
                    ServiceMapping.TOURNAMENT_SATELLITE.getServiceName(), satelliteAggregateId, executionAggregateId,
                    userAggregateId, name, username, eventVersion);
            commandGateway.send(anonymizeUserCommand);
        }, new ArrayList<>(Arrays.asList(anonymizeCoreStep)));

        this.workflow.addStep(getSatelliteStep);
        this.workflow.addStep(anonymizeCoreStep);
        this.workflow.addStep(anonymizeSatelliteStep);
    }
}
