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
import org.anonymous.msrefactor.quizzes.commands.tournamentSatellite.FindTournamentSatelliteByTournamentIdCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatellite.GetTournamentSatelliteByIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatelliteDto;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.sagas.states.TournamentSatelliteSagaState;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.util.ArrayList;
import java.util.Arrays;

public class FindParticipantFunctionalitySagas extends WorkflowFunctionality {
    private TournamentSatelliteDto tournamentSatellite;
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
            FindTournamentSatelliteByTournamentIdCommand findCommand = new FindTournamentSatelliteByTournamentIdCommand(unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE.getServiceName(), tournamentAggregateId);
            this.setTournamentSatellite((TournamentSatelliteDto) commandGateway.send(findCommand));
        });

        SagaStep getSatelliteStep = new SagaStep("getSatelliteStep", () -> {
            GetTournamentSatelliteByIdCommand getCommand = new GetTournamentSatelliteByIdCommand(unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE.getServiceName(), getTournamentSatellite().getAggregateId());
            SagaCommand sagaCommand = new SagaCommand(getCommand);
            sagaCommand.setSemanticLock(TournamentSatelliteSagaState.READ_SATELLITE);
            this.setTournamentSatellite((TournamentSatelliteDto) commandGateway.send(sagaCommand));
        }, new ArrayList<>(Arrays.asList(findSatelliteStep)));

        getSatelliteStep.registerCompensation(() -> {
            Command command = new Command(unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE.getServiceName(), getTournamentSatellite().getAggregateId());
            SagaCommand sagaCommand = new SagaCommand(command);
            sagaCommand.setSemanticLock(GenericSagaState.NOT_IN_SAGA);
            commandGateway.send(sagaCommand);
        }, unitOfWork);

        SagaStep getParticipantStep = new SagaStep("getParticipantStep", () -> {
            UserDto participant = getTournamentSatellite().getParticipants().stream()
                    .filter(p -> p.getAggregateId().equals(userAggregateId)).findFirst().orElse(null);
            this.setParticipant(participant);
        }, new ArrayList<>(Arrays.asList(getSatelliteStep)));

        this.workflow.addStep(findSatelliteStep);
        this.workflow.addStep(getSatelliteStep);
        this.workflow.addStep(getParticipantStep);
    }

    public void setTournamentSatellite(TournamentSatelliteDto tournamentSatellite) {
        this.tournamentSatellite = tournamentSatellite;
    }

    public TournamentSatelliteDto getTournamentSatellite() {
        return tournamentSatellite;
    }

    public void setParticipant(UserDto participant) {
        this.participant = participant;
    }

    public UserDto getParticipant() {
        return this.participant;
    }
}
