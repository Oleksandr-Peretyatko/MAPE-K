package org.anonymous.msrefactor.quizzes.microservices.tournament.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.tournament.GetClosedTournamentsForCourseExecutionCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatellite.FindTournamentSatelliteByTournamentIdCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatelliteSatellite.FindTournamentSatelliteSatelliteByTournamentIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.TournamentSatelliteSatelliteDto;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatelliteDto;

import java.util.ArrayList;
import java.util.Arrays;
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

        
        // compose creator/participants from the TournamentSatellite aggregate — one
        // remote read per tournament is the composition cost of the split
        SagaStep composeSatellitesStep = new SagaStep("composeSatellitesStep", () -> {
            for (TournamentDto tournamentDto : getClosedTournaments()) {
                FindTournamentSatelliteByTournamentIdCommand findSatelliteCommand = new FindTournamentSatelliteByTournamentIdCommand(unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE.getServiceName(), tournamentDto.getAggregateId());
                TournamentSatelliteDto satelliteDto = (TournamentSatelliteDto) commandGateway.send(findSatelliteCommand);
                tournamentDto.setCreator(satelliteDto.getCreator());
                FindTournamentSatelliteSatelliteByTournamentIdCommand findRosterCommand = new FindTournamentSatelliteSatelliteByTournamentIdCommand(unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE_SATELLITE.getServiceName(), tournamentDto.getAggregateId());
                TournamentSatelliteSatelliteDto rosterDto = (TournamentSatelliteSatelliteDto) commandGateway.send(findRosterCommand);
                tournamentDto.setParticipants(rosterDto.getParticipants());
            }
        }, new ArrayList<>(Arrays.asList(getClosedTournamentsStep)));

        workflow.addStep(getClosedTournamentsStep);
        workflow.addStep(composeSatellitesStep);
    }

    public List<TournamentDto> getClosedTournaments() {
        return closedTournaments;
    }

    public void setClosedTournaments(List<TournamentDto> closedTournaments) {
        this.closedTournaments = closedTournaments;
    }
}