package org.anonymous.msrefactor.quizzes.microservices.tournament.coordination.sagas;

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
import org.anonymous.msrefactor.quizzes.commands.tournament.GetTournamentByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatellite.FindTournamentSatelliteByTournamentIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.sagas.states.TournamentSagaState;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatelliteDto;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.util.ArrayList;
import java.util.Arrays;

public class FindParticipantFunctionalitySagas extends WorkflowFunctionality {
    private TournamentDto tournament;
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

        SagaStep getTournamentStep = new SagaStep("getTournamentStep", () -> {
            GetTournamentByIdCommand getTournamentByIdCommand = new GetTournamentByIdCommand(unitOfWork, ServiceMapping.TOURNAMENT.getServiceName(), tournamentAggregateId);
            SagaCommand sagaCommand = new SagaCommand(getTournamentByIdCommand);
            sagaCommand.setSemanticLock(TournamentSagaState.READ_TOURNAMENT);
            TournamentDto tournament = (TournamentDto) commandGateway.send(sagaCommand);
            this.setTournament(tournament);
        });

        getTournamentStep.registerCompensation(() -> {
            Command command = new Command(unitOfWork, ServiceMapping.TOURNAMENT.getServiceName(), tournamentAggregateId);
            SagaCommand sagaCommand = new SagaCommand(command);
            sagaCommand.setSemanticLock(GenericSagaState.NOT_IN_SAGA);
            commandGateway.send(sagaCommand);
        }, unitOfWork);

        SagaStep getParticipantStep = new SagaStep("getParticipantStep", () -> {
            FindTournamentSatelliteByTournamentIdCommand command = new FindTournamentSatelliteByTournamentIdCommand(
                    unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE.getServiceName(), tournamentAggregateId);
            TournamentSatelliteDto satelliteDto = (TournamentSatelliteDto) commandGateway.send(command);
            UserDto participant = satelliteDto == null ? null : satelliteDto.getParticipants().stream()
                    .filter(p -> p.getAggregateId().equals(userAggregateId)).findFirst().orElse(null);
            this.setParticipant(participant);
        }, new ArrayList<>(Arrays.asList(getTournamentStep)));

        this.workflow.addStep(getTournamentStep);
        this.workflow.addStep(getParticipantStep);
    }

    public void setTournament(TournamentDto tournament) {
        this.tournament = tournament;
    }

    public TournamentDto getTournament() {
        return tournament;
    }

    public void setParticipant(UserDto participant) {
        this.participant = participant;
    }

    public UserDto getParticipant() {
        return this.participant;
    }
}
