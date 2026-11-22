package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.messaging;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.messaging.CommandHandler;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatelliteSatellite.*;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.service.TournamentSatelliteSatelliteService;

import java.util.logging.Logger;

@Component
public class TournamentSatelliteSatelliteCommandHandler extends CommandHandler {
    private static final Logger logger = Logger.getLogger(TournamentSatelliteSatelliteCommandHandler.class.getName());

    @Autowired
    private TournamentSatelliteSatelliteService tournamentSatelliteSatelliteService;

    @Override
    public String getAggregateTypeName() {
        return "TournamentSatelliteSatellite";
    }

    @Override
    public Object handleDomainCommand(Command command) {
        return switch (command) {
            case CreateTournamentSatelliteSatelliteCommand cmd -> handleCreate(cmd);
            case FindTournamentSatelliteSatelliteByTournamentIdCommand cmd -> handleFindByTournamentId(cmd);
            case GetTournamentSatelliteSatelliteByIdCommand cmd -> handleGetById(cmd);
            case AddParticipantCommand cmd -> handleAddParticipant(cmd);
            case LeaveTournamentCommand cmd -> handleLeaveTournament(cmd);
            case SolveQuizCommand cmd -> handleSolveQuiz(cmd);
            case UpdateParticipantAnswerCommand cmd -> handleUpdateParticipantAnswer(cmd);
            case AnonymizeUserCommand cmd -> handleAnonymizeUser(cmd);
            case UpdateUserNameCommand cmd -> handleUpdateUserName(cmd);
            case RemoveUserCommand cmd -> handleRemoveUser(cmd);
            case UpdateTournamentSatelliteSatelliteScalarsCommand cmd -> handleUpdateScalars(cmd);
            case RemoveTournamentSatelliteSatelliteCommand cmd -> handleRemove(cmd);
            default -> {
                logger.warning("Unknown command type: " + command.getClass().getName());
                yield null;
            }
        };
    }

    private Object handleCreate(CreateTournamentSatelliteSatelliteCommand command) {
        return tournamentSatelliteSatelliteService.createTournamentSatelliteSatellite(
                command.getTournamentAggregateId(),
                command.getCreatorDto(),
                command.getCourseExecutionAggregateId(),
                command.getCourseExecutionVersion(),
                command.getStartTime(),
                command.isCancelled(),
                command.getUnitOfWork());
    }

    private Object handleFindByTournamentId(FindTournamentSatelliteSatelliteByTournamentIdCommand command) {
        return tournamentSatelliteSatelliteService.findTournamentSatelliteSatelliteByTournamentId(
                command.getTournamentAggregateId(),
                command.getUnitOfWork());
    }

    private Object handleGetById(GetTournamentSatelliteSatelliteByIdCommand command) {
        return tournamentSatelliteSatelliteService.getTournamentSatelliteSatelliteById(
                command.getSatelliteAggregateId(),
                command.getUnitOfWork());
    }

    private Object handleAddParticipant(AddParticipantCommand command) {
        tournamentSatelliteSatelliteService.addParticipant(
                command.getTournamentAggregateId(),
                command.getUserDto(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleLeaveTournament(LeaveTournamentCommand command) {
        tournamentSatelliteSatelliteService.leaveTournament(
                command.getTournamentAggregateId(),
                command.getUserAggregateId(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleSolveQuiz(SolveQuizCommand command) {
        tournamentSatelliteSatelliteService.solveQuiz(
                command.getTournamentAggregateId(),
                command.getUserAggregateId(),
                command.getAnswerAggregateId(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleUpdateParticipantAnswer(UpdateParticipantAnswerCommand command) {
        return tournamentSatelliteSatelliteService.updateParticipantAnswer(
                command.getSatelliteAggregateId(),
                command.getStudentAggregateId(),
                command.getQuizAnswerAggregateId(),
                command.getQuestionAggregateId(),
                command.isCorrect(),
                command.getEventVersion(),
                command.getUnitOfWork());
    }

    private Object handleAnonymizeUser(AnonymizeUserCommand command) {
        return tournamentSatelliteSatelliteService.anonymizeUser(
                command.getSatelliteAggregateId(),
                command.getExecutionAggregateId(),
                command.getUserAggregateId(),
                command.getName(),
                command.getUsername(),
                command.getEventVersion(),
                command.getUnitOfWork());
    }

    private Object handleUpdateUserName(UpdateUserNameCommand command) {
        tournamentSatelliteSatelliteService.updateUserName(
                command.getSatelliteAggregateId(),
                command.getExecutionAggregateId(),
                command.getEventVersion(),
                command.getUserAggregateId(),
                command.getName(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleRemoveUser(RemoveUserCommand command) {
        return tournamentSatelliteSatelliteService.removeUser(
                command.getSatelliteAggregateId(),
                command.getExecutionAggregateId(),
                command.getUserAggregateId(),
                command.getEventVersion(),
                command.getUnitOfWork());
    }

    private Object handleUpdateScalars(UpdateTournamentSatelliteSatelliteScalarsCommand command) {
        tournamentSatelliteSatelliteService.updateScalars(
                command.getTournamentAggregateId(),
                command.getStartTime(),
                command.getCancelled(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleRemove(RemoveTournamentSatelliteSatelliteCommand command) {
        tournamentSatelliteSatelliteService.removeTournamentSatelliteSatellite(
                command.getTournamentAggregateId(),
                command.getUnitOfWork());
        return null;
    }
}
