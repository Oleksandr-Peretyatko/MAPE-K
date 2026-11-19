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
import org.anonymous.msrefactor.quizzes.commands.tournament.UpdateUserNameCommand;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.sagas.states.TournamentSagaState;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.util.ArrayList;
import java.util.Arrays;

public class UpdateUserNameFunctionalitySagas extends WorkflowFunctionality {
    private TournamentDto tournament;
    private UserDto participant;
    private final String name;
    private final CommandGateway commandGateway;
    private final Long eventVersion;
    private final Integer executionAggregateId;
    private final SagaUnitOfWorkService unitOfWorkService;

    public UpdateUserNameFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, Long eventVersion, Integer tournamentAggregateId,
                                            Integer executionAggregateId, Integer userAggregateId, SagaUnitOfWork unitOfWork, String name,
                                            CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.eventVersion = eventVersion;
        this.executionAggregateId = executionAggregateId;
        this.name = name;
        this.commandGateway = commandGateway;
        this.buildWorkflow(tournamentAggregateId, userAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer tournamentAggregateId, Integer userAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getTournamentStep = new SagaStep("getTournamentStep", () -> {
            GetTournamentByIdCommand getTournamentByIdCommand = new GetTournamentByIdCommand(unitOfWork, ServiceMapping.TOURNAMENT.getServiceName(), tournamentAggregateId);
            SagaCommand sagaCommand = new SagaCommand(getTournamentByIdCommand);
            sagaCommand.setSemanticLock(TournamentSagaState.READ_TOURNAMENT);
            TournamentDto tournamentDTO = (TournamentDto) commandGateway.send(sagaCommand);
            this.setTournament(tournamentDTO);

        });

        getTournamentStep.registerCompensation(() -> {
            Command command = new Command(unitOfWork, ServiceMapping.TOURNAMENT.getServiceName(), tournamentAggregateId);
            SagaCommand sagaCommand = new SagaCommand(command);
            sagaCommand.setSemanticLock(GenericSagaState.NOT_IN_SAGA);
            commandGateway.send(sagaCommand);
        }, unitOfWork);

        SagaStep getParticipantStep = new SagaStep("getParticipantStep", () -> {
            UserDto participant = this.tournament.getParticipants().stream()
                    .filter(p -> p.getAggregateId().equals(userAggregateId)).findFirst().orElse(null);
            this.setParticipant(participant);
        }, new ArrayList<>(Arrays.asList(getTournamentStep)));

        getParticipantStep.registerCompensation(() -> {
            Command command = new Command(unitOfWork, ServiceMapping.USER.getServiceName(), userAggregateId);
            SagaCommand sagaCommand = new SagaCommand(command);
            sagaCommand.setSemanticLock(GenericSagaState.NOT_IN_SAGA);
            commandGateway.send(sagaCommand);
        }, unitOfWork);

        SagaStep updateParticipantNameStep = new SagaStep("updateParticipantNameStep", () -> {
            UpdateUserNameCommand updateUserNameCommand = new UpdateUserNameCommand(unitOfWork, ServiceMapping.TOURNAMENT.getServiceName(), tournamentAggregateId, executionAggregateId, eventVersion, userAggregateId, name);
            commandGateway.send(updateUserNameCommand);
            this.setParticipant(getParticipant());
            this.setTournament(getTournament());
        }, new ArrayList<>(Arrays.asList(getTournamentStep, getParticipantStep)));

        this.workflow.addStep(getTournamentStep);
        this.workflow.addStep(getParticipantStep);
        this.workflow.addStep(updateParticipantNameStep);
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
