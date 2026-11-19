package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.coordination.sagas;

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
import org.anonymous.msrefactor.quizzes.commands.tournamentSatelliteSatellite.FindTournamentSatelliteSatelliteByTournamentIdCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatelliteSatellite.GetTournamentSatelliteSatelliteByIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.TournamentSatelliteSatelliteDto;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.sagas.states.TournamentSatelliteSatelliteSagaState;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.util.ArrayList;
import java.util.Arrays;

public class FindParticipantFunctionalitySagas extends WorkflowFunctionality {
    private TournamentSatelliteSatelliteDto tournamentSatelliteSatellite;
    private UserDto participant;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public FindParticipantFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, Integer tournamentAggregateId, Integer userAggregateId,
                                             SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(tournamentAggregateId, userAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer tournamentAggregateId, Integer userAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep findSatelliteStep = new SagaStep("findSatelliteStep", () -> {
            FindTournamentSatelliteSatelliteByTournamentIdCommand findCommand = new FindTournamentSatelliteSatelliteByTournamentIdCommand(unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE_SATELLITE.getServiceName(), tournamentAggregateId);
            this.setTournamentSatelliteSatellite((TournamentSatelliteSatelliteDto) commandGateway.send(findCommand));
        });

        SagaStep getSatelliteStep = new SagaStep("getSatelliteStep", () -> {
            GetTournamentSatelliteSatelliteByIdCommand getCommand = new GetTournamentSatelliteSatelliteByIdCommand(unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE_SATELLITE.getServiceName(), getTournamentSatelliteSatellite().getAggregateId());
            SagaCommand sagaCommand = new SagaCommand(getCommand);
            sagaCommand.setSemanticLock(TournamentSatelliteSatelliteSagaState.READ_SATELLITE_SATELLITE);
            this.setTournamentSatelliteSatellite((TournamentSatelliteSatelliteDto) commandGateway.send(sagaCommand));
        }, new ArrayList<>(Arrays.asList(findSatelliteStep)));

        getSatelliteStep.registerCompensation(() -> {
            Command command = new Command(unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE_SATELLITE.getServiceName(), getTournamentSatelliteSatellite().getAggregateId());
            SagaCommand sagaCommand = new SagaCommand(command);
            sagaCommand.setSemanticLock(GenericSagaState.NOT_IN_SAGA);
            commandGateway.send(sagaCommand);
        }, unitOfWork);

        SagaStep getParticipantStep = new SagaStep("getParticipantStep", () -> {
            UserDto participant = getTournamentSatelliteSatellite().getParticipants().stream()
                    .filter(p -> p.getAggregateId().equals(userAggregateId)).findFirst().orElse(null);
            this.setParticipant(participant);
        }, new ArrayList<>(Arrays.asList(getSatelliteStep)));

        this.workflow.addStep(findSatelliteStep);
        this.workflow.addStep(getSatelliteStep);
        this.workflow.addStep(getParticipantStep);
    }

    public void setTournamentSatelliteSatellite(TournamentSatelliteSatelliteDto tournamentSatelliteSatellite) {
        this.tournamentSatelliteSatellite = tournamentSatelliteSatellite;
    }

    public TournamentSatelliteSatelliteDto getTournamentSatelliteSatellite() {
        return tournamentSatelliteSatellite;
    }

    public void setParticipant(UserDto participant) {
        this.participant = participant;
    }

    public UserDto getParticipant() {
        return this.participant;
    }
}
