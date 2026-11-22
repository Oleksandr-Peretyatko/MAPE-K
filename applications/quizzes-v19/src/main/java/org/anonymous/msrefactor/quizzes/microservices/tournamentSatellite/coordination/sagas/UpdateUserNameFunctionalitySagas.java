package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.coordination.sagas;

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
import org.anonymous.msrefactor.quizzes.commands.tournamentSatellite.GetTournamentSatelliteByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatellite.UpdateUserNameCommand;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatelliteDto;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.sagas.states.TournamentSatelliteSagaState;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.util.ArrayList;
import java.util.Arrays;

public class UpdateUserNameFunctionalitySagas extends WorkflowFunctionality {
    private TournamentSatelliteDto tournamentSatellite;
    private UserDto participant;
    private final String name;
    private final CommandGateway commandGateway;
    private final Long eventVersion;
    private final Integer executionAggregateId;
    private final SagaUnitOfWorkService unitOfWorkService;

    public UpdateUserNameFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, Long eventVersion, Integer satelliteAggregateId,
                                            Integer executionAggregateId, Integer userAggregateId, SagaUnitOfWork unitOfWork, String name,
                                            CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.eventVersion = eventVersion;
        this.executionAggregateId = executionAggregateId;
        this.name = name;
        this.commandGateway = commandGateway;
        this.buildWorkflow(satelliteAggregateId, userAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer satelliteAggregateId, Integer userAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getSatelliteStep = new SagaStep("getSatelliteStep", () -> {
            GetTournamentSatelliteByIdCommand getCommand = new GetTournamentSatelliteByIdCommand(unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE.getServiceName(), satelliteAggregateId);
            SagaCommand sagaCommand = new SagaCommand(getCommand);
            sagaCommand.setSemanticLock(TournamentSatelliteSagaState.READ_SATELLITE);
            this.tournamentSatellite = (TournamentSatelliteDto) commandGateway.send(sagaCommand);
        });

        getSatelliteStep.registerCompensation(() -> {
            Command command = new Command(unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE.getServiceName(), satelliteAggregateId);
            SagaCommand sagaCommand = new SagaCommand(command);
            sagaCommand.setSemanticLock(GenericSagaState.NOT_IN_SAGA);
            commandGateway.send(sagaCommand);
        }, unitOfWork);

        SagaStep getParticipantStep = new SagaStep("getParticipantStep", () -> {
            UserDto participant = this.tournamentSatellite.getParticipants().stream()
                    .filter(p -> p.getAggregateId().equals(userAggregateId)).findFirst().orElse(null);
            this.setParticipant(participant);
        }, new ArrayList<>(Arrays.asList(getSatelliteStep)));

        getParticipantStep.registerCompensation(() -> {
            Command command = new Command(unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE.getServiceName(), satelliteAggregateId);
            SagaCommand sagaCommand = new SagaCommand(command);
            sagaCommand.setSemanticLock(GenericSagaState.NOT_IN_SAGA);
            commandGateway.send(sagaCommand);
        }, unitOfWork);

        SagaStep updateParticipantNameStep = new SagaStep("updateParticipantNameStep", () -> {
            UpdateUserNameCommand updateUserNameCommand = new UpdateUserNameCommand(unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE.getServiceName(), satelliteAggregateId, executionAggregateId, eventVersion, userAggregateId, name);
            commandGateway.send(updateUserNameCommand);
            this.setParticipant(getParticipant());
        }, new ArrayList<>(Arrays.asList(getSatelliteStep, getParticipantStep)));

        this.workflow.addStep(getSatelliteStep);
        this.workflow.addStep(getParticipantStep);
        this.workflow.addStep(updateParticipantNameStep);
    }

    public TournamentSatelliteDto getTournamentSatellite() {
        return tournamentSatellite;
    }

    public void setTournamentSatellite(TournamentSatelliteDto tournamentSatellite) {
        this.tournamentSatellite = tournamentSatellite;
    }

    public void setParticipant(UserDto participant) {
        this.participant = participant;
    }

    public UserDto getParticipant() {
        return this.participant;
    }
}
