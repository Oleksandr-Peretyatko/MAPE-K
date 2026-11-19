package org.anonymous.msrefactor.quizzes.microservices.tournament.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.messaging.SagaCommand;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.quiz.RemoveQuizCommand;
import org.anonymous.msrefactor.quizzes.commands.tournament.GetTournamentByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.tournament.RemoveTournamentCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatellite.FindTournamentSatelliteByTournamentIdCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatellite.RemoveTournamentSatelliteCommand;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatelliteDto;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.sagas.states.TournamentSagaState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RemoveTournamentFunctionalitySagas extends WorkflowFunctionality {
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
            GetTournamentByIdCommand getTournamentByIdCommand = new GetTournamentByIdCommand(unitOfWork, ServiceMapping.TOURNAMENT.getServiceName(), tournamentAggregateId);
            SagaCommand sagaCommand = new SagaCommand(getTournamentByIdCommand);
            sagaCommand.setForbiddenStates(new ArrayList<>(List.of(TournamentSagaState.IN_UPDATE_TOURNAMENT)));
            sagaCommand.setSemanticLock(TournamentSagaState.IN_DELETE_TOURNAMENT);
            TournamentDto tournamentDto = (TournamentDto) commandGateway.send(sagaCommand);
            setTournamentDto(tournamentDto);
        });

        /*
         * DELETE — this.state == DELETED => this.participants.empty. The participants
         * live on the TournamentSatellite aggregate since the split, so the rule is
         * enforced here, across the boundary, before anything is removed; the
         * satellite's own verifyInvariants() re-checks it when the satellite is
         * removed.
         */
        SagaStep getSatelliteStep = new SagaStep("getSatelliteStep", () -> {
            FindTournamentSatelliteByTournamentIdCommand findSatelliteCommand = new FindTournamentSatelliteByTournamentIdCommand(unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE.getServiceName(), tournamentAggregateId);
            TournamentSatelliteDto satelliteDto = (TournamentSatelliteDto) commandGateway.send(findSatelliteCommand);
            if (satelliteDto != null && satelliteDto.getParticipants() != null && !satelliteDto.getParticipants().isEmpty()) {
                throw new QuizzesException(org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage.INVARIANT_BREAK, tournamentAggregateId);
            }
        }, new ArrayList<>(Arrays.asList(getTournamentStep)));

        SagaStep removeQuizStep = new SagaStep("removeQuizStep", () -> {
            RemoveQuizCommand removeQuizCommand = new RemoveQuizCommand(unitOfWork, ServiceMapping.QUIZ.getServiceName(), getTournamentDto().getQuiz().getAggregateId());
            commandGateway.send(removeQuizCommand);
        }, new ArrayList<>(Arrays.asList(getSatelliteStep)));

        SagaStep removeTournamentStep = new SagaStep("removeTournamentStep", () -> {
            RemoveTournamentCommand removeTournamentCommand = new RemoveTournamentCommand(unitOfWork, ServiceMapping.TOURNAMENT.getServiceName(), tournamentAggregateId);
            commandGateway.send(removeTournamentCommand);
        }, new ArrayList<>(Arrays.asList(removeQuizStep)));

        // the satellite does not outlive the core: its removal is the saga's last step
        SagaStep removeSatelliteStep = new SagaStep("removeSatelliteStep", () -> {
            RemoveTournamentSatelliteCommand removeSatelliteCommand = new RemoveTournamentSatelliteCommand(unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE.getServiceName(), tournamentAggregateId);
            commandGateway.send(removeSatelliteCommand);
        }, new ArrayList<>(Arrays.asList(removeTournamentStep)));

        workflow.addStep(getTournamentStep);
        workflow.addStep(getSatelliteStep);
        workflow.addStep(removeQuizStep);
        workflow.addStep(removeTournamentStep);
        workflow.addStep(removeSatelliteStep);
    }

    public TournamentDto getTournamentDto() {
        return tournamentDto;
    }

    public void setTournamentDto(TournamentDto tournamentDto) {
        this.tournamentDto = tournamentDto;
    }
}
