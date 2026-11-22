package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.SagaAggregate;
import org.anonymous.msrefactor.ms.transaction.sagas.messaging.SagaCommand;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.execution.GetStudentByExecutionIdAndUserIdCommand;
import org.anonymous.msrefactor.quizzes.commands.tournament.GetTournamentByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatellite.FindTournamentSatelliteByTournamentIdCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatelliteSatellite.AddParticipantCommand;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatelliteDto;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.sagas.states.TournamentSagaState;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class AddParticipantFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private UserDto userDto;
    private TournamentDto tournamentDto;
    private final CommandGateway commandGateway;

    public AddParticipantFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                            Integer tournamentAggregateId, Integer executionAggregateId, Integer userAggregateId,
                                            SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(tournamentAggregateId, executionAggregateId, userAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer tournamentAggregateId, Integer executionAggregateId, Integer userAggregateId,
            SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getUserStep = new SagaStep("getUserStep", () -> {
            GetStudentByExecutionIdAndUserIdCommand getStudentCommand = new GetStudentByExecutionIdAndUserIdCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), executionAggregateId, userAggregateId);
            this.userDto = (UserDto) commandGateway.send(getStudentCommand);
        });

        // the date/cancelled check stays on the core: the fresh scalars are read here
        // (guarded by the same forbidden state the add used to carry) and passed to
        // the satellite so its enrollment invariants run against current values
        SagaStep getTournamentStep = new SagaStep("getTournamentStep", () -> {
            List<SagaAggregate.SagaState> states = new ArrayList<>();
            states.add(TournamentSagaState.IN_UPDATE_TOURNAMENT);
            GetTournamentByIdCommand getTournamentByIdCommand = new GetTournamentByIdCommand(unitOfWork, ServiceMapping.TOURNAMENT.getServiceName(), tournamentAggregateId);
            SagaCommand sagaCommand = new SagaCommand(getTournamentByIdCommand);
            sagaCommand.setForbiddenStates(states);
            this.tournamentDto = (TournamentDto) commandGateway.send(sagaCommand);
        }, new ArrayList<>(Arrays.asList(getUserStep)));

        /*
         * CREATOR_PARTICIPANT_CONSISTENCY — intra-aggregate before the roster split.
         * The creator replica lives on TournamentSatellite now, so the only moment
         * the rule can break synchronously — adding the creator as a participant
         * whose User data disagrees with the creator replica — is guarded here,
         * across the boundary; afterwards the shared user-event subscriptions keep
         * the two replicas converging.
         */
        SagaStep checkCreatorStep = new SagaStep("checkCreatorStep", () -> {
            FindTournamentSatelliteByTournamentIdCommand findCreatorCommand = new FindTournamentSatelliteByTournamentIdCommand(unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE.getServiceName(), tournamentAggregateId);
            TournamentSatelliteDto satelliteDto = (TournamentSatelliteDto) commandGateway.send(findCreatorCommand);
            UserDto creator = satelliteDto == null ? null : satelliteDto.getCreator();
            // an anonymous user is rejected by the add itself (USER_IS_ANONYMOUS), as
            // before the split — the consistency guard must not pre-empt that error
            if (creator != null && this.userDto != null
                    && !"ANONYMOUS".equals(this.userDto.getName()) && !"ANONYMOUS".equals(this.userDto.getUsername())
                    && this.userDto.getAggregateId().equals(creator.getAggregateId())
                    && (!Objects.equals(this.userDto.getVersion(), creator.getVersion())
                            || !Objects.equals(this.userDto.getName(), creator.getName())
                            || !Objects.equals(this.userDto.getUsername(), creator.getUsername()))) {
                throw new QuizzesException(QuizzesErrorMessage.INVARIANT_BREAK, tournamentAggregateId);
            }
        }, new ArrayList<>(Arrays.asList(getTournamentStep)));

        SagaStep addParticipantStep = new SagaStep("addParticipantStep", () -> {
            AddParticipantCommand addParticipantCommand = new AddParticipantCommand(unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE_SATELLITE.getServiceName(), tournamentAggregateId, this.userDto, this.tournamentDto);
            commandGateway.send(addParticipantCommand);
        }, new ArrayList<>(Arrays.asList(checkCreatorStep)));

        this.workflow.addStep(getUserStep);
        this.workflow.addStep(getTournamentStep);
        this.workflow.addStep(checkCreatorStep);
        this.workflow.addStep(addParticipantStep);
    }

    public void setUserDto(UserDto userDto) {
        this.userDto = userDto;
    }

    public UserDto getUserDto() {
        return userDto;
    }
}
