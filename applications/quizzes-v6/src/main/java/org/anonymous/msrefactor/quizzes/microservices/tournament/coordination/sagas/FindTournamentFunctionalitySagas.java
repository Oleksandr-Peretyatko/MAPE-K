package org.anonymous.msrefactor.quizzes.microservices.tournament.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.tournament.GetTournamentByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatellite.FindTournamentSatelliteByTournamentIdCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatelliteSatellite.FindTournamentSatelliteSatelliteByTournamentIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatelliteDto;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.TournamentSatelliteSatelliteDto;

import java.util.ArrayList;
import java.util.Arrays;

public class FindTournamentFunctionalitySagas extends WorkflowFunctionality {
    private TournamentDto tournamentDto;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public FindTournamentFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                            Integer tournamentAggregateId, SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(tournamentAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer tournamentAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep findTournamentStep = new SagaStep("findTournamentStep", () -> {
            GetTournamentByIdCommand getTournamentByIdCommand = new GetTournamentByIdCommand(unitOfWork, ServiceMapping.TOURNAMENT.getServiceName(), tournamentAggregateId);
            TournamentDto tournamentDto = (TournamentDto) commandGateway.send(getTournamentByIdCommand);
            this.setTournamentDto(tournamentDto);
        });

        SagaStep composeSatelliteStep = new SagaStep("composeSatelliteStep", () -> {
            FindTournamentSatelliteByTournamentIdCommand command = new FindTournamentSatelliteByTournamentIdCommand(
                    unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE.getServiceName(), tournamentAggregateId);
            TournamentSatelliteDto satelliteDto = (TournamentSatelliteDto) commandGateway.send(command);
            if (satelliteDto != null) {
                this.tournamentDto.setCreator(satelliteDto.getCreator());
            }
        }, new ArrayList<>(Arrays.asList(findTournamentStep)));

        SagaStep composeSatelliteSatelliteStep = new SagaStep("composeSatelliteSatelliteStep", () -> {
            FindTournamentSatelliteSatelliteByTournamentIdCommand command = new FindTournamentSatelliteSatelliteByTournamentIdCommand(
                    unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE_SATELLITE.getServiceName(), tournamentAggregateId);
            TournamentSatelliteSatelliteDto satelliteDto = (TournamentSatelliteSatelliteDto) commandGateway
                    .send(command);
            if (satelliteDto != null) {
                this.tournamentDto.setParticipants(satelliteDto.getParticipants());
            }
        }, new ArrayList<>(Arrays.asList(composeSatelliteStep)));

        workflow.addStep(findTournamentStep);
        workflow.addStep(composeSatelliteStep);
        workflow.addStep(composeSatelliteSatelliteStep);
    }

    public TournamentDto getTournamentDto() {
        return tournamentDto;
    }

    public void setTournamentDto(TournamentDto tournamentDto) {
        this.tournamentDto = tournamentDto;
    }
}
