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
            case GetTournamentSatelliteSatelliteByIdCommand cmd -> handleGetTournamentSatelliteSatelliteById(cmd);
            case FindTournamentSatelliteSatelliteByTournamentIdCommand cmd -> handleFindTournamentSatelliteSatelliteByTournamentId(cmd);
            case CreateTournamentSatelliteSatelliteCommand cmd -> handleCreateTournamentSatelliteSatellite(cmd);
            case AddParticipantCommand cmd -> handleAddParticipant(cmd);
            case LeaveTournamentCommand cmd -> handleLeaveTournament(cmd);
            case SolveQuizCommand cmd -> handleSolveQuiz(cmd);
            case UpdateTournamentSatelliteSatelliteInfoCommand cmd -> handleUpdateTournamentSatelliteSatelliteInfo(cmd);
            case DeactivateTournamentSatelliteSatelliteCommand cmd -> handleDeactivateTournamentSatelliteSatellite(cmd);
            case RemoveTournamentSatelliteSatelliteCommand cmd -> handleRemoveTournamentSatelliteSatellite(cmd);
            case AnonymizeUserCommand cmd -> handleAnonymizeUser(cmd);
            case RemoveUserCommand cmd -> handleRemoveUser(cmd);
            case UpdateUserNameCommand cmd -> handleUpdateUserName(cmd);
            case UpdateParticipantAnswerCommand cmd -> handleUpdateParticipantAnswer(cmd);
            default -> {
                logger.warning("Unknown command type: " + command.getClass().getName());
                yield null;
            }
        };
    }

    private Object handleGetTournamentSatelliteSatelliteById(GetTournamentSatelliteSatelliteByIdCommand command) {
        logger.info("Getting tournament satellite satellite: " + command.getAggregateId());
        return tournamentSatelliteSatelliteService.getTournamentSatelliteSatelliteById(
                command.getAggregateId(),
                command.getUnitOfWork());
    }

    private Object handleFindTournamentSatelliteSatelliteByTournamentId(
            FindTournamentSatelliteSatelliteByTournamentIdCommand command) {
        logger.info("Finding tournament satellite satellite by tournament: " + command.getTournamentAggregateId());
        return tournamentSatelliteSatelliteService.findTournamentSatelliteSatelliteByTournamentId(
                command.getTournamentAggregateId(),
                command.getUnitOfWork());
    }

    private Object handleCreateTournamentSatelliteSatellite(CreateTournamentSatelliteSatelliteCommand command) {
        logger.info("Creating tournament satellite satellite for tournament: " + command.getTournamentAggregateId());
        return tournamentSatelliteSatelliteService.createTournamentSatelliteSatellite(
                command.getTournamentAggregateId(),
                command.getTournamentSatelliteAggregateId(),
                command.getTournamentDto(),
                command.getCourseExecutionDto(),
                command.getUnitOfWork());
    }

    private Object handleAddParticipant(AddParticipantCommand command) {
        logger.info("Adding participant to tournament: " + command.getTournamentAggregateId());
        tournamentSatelliteSatelliteService.addParticipant(
                command.getTournamentAggregateId(),
                command.getUserDto(),
                command.getTournamentDto(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleLeaveTournament(LeaveTournamentCommand command) {
        logger.info("Leaving tournament: " + command.getTournamentAggregateId());
        tournamentSatelliteSatelliteService.leaveTournament(
                command.getTournamentAggregateId(),
                command.getUserAggregateId(),
                command.getTournamentDto(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleSolveQuiz(SolveQuizCommand command) {
        logger.info("Solving quiz for tournament: " + command.getTournamentAggregateId());
        tournamentSatelliteSatelliteService.solveQuiz(
                command.getTournamentAggregateId(),
                command.getUserAggregateId(),
                command.getAnswerAggregateId(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleUpdateTournamentSatelliteSatelliteInfo(UpdateTournamentSatelliteSatelliteInfoCommand command) {
        logger.info("Updating replicated tournament info for tournament: " + command.getTournamentAggregateId());
        return tournamentSatelliteSatelliteService.updateTournamentInfoByTournamentId(
                command.getTournamentAggregateId(),
                command.getStartTime(),
                command.isCancelled(),
                command.getTournamentVersion(),
                command.getUnitOfWork());
    }

    private Object handleDeactivateTournamentSatelliteSatellite(DeactivateTournamentSatelliteSatelliteCommand command) {
        logger.info("Deactivating tournament satellite satellite for tournament: " + command.getTournamentAggregateId());
        tournamentSatelliteSatelliteService.deactivateTournamentSatelliteSatellite(
                command.getTournamentAggregateId(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleRemoveTournamentSatelliteSatellite(RemoveTournamentSatelliteSatelliteCommand command) {
        logger.info("Removing tournament satellite satellite for tournament: " + command.getTournamentAggregateId());
        tournamentSatelliteSatelliteService.removeTournamentSatelliteSatellite(
                command.getTournamentAggregateId(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleAnonymizeUser(AnonymizeUserCommand command) {
        logger.info("Anonymizing user for tournament satellite satellite: " + command.getSatelliteAggregateId());
        return tournamentSatelliteSatelliteService.anonymizeUser(
                command.getSatelliteAggregateId(),
                command.getExecutionAggregateId(),
                command.getUserAggregateId(),
                command.getName(),
                command.getUsername(),
                command.getEventVersion(),
                command.getUnitOfWork());
    }

    private Object handleRemoveUser(RemoveUserCommand command) {
        logger.info("Removing user " + command.getUserAggregateId() + " from tournament satellite satellite "
                + command.getSatelliteAggregateId());
        return tournamentSatelliteSatelliteService.removeUser(
                command.getSatelliteAggregateId(),
                command.getCourseExecutionAggregateId(),
                command.getUserAggregateId(),
                command.getEventVersion(),
                command.getUnitOfWork());
    }

    private Object handleUpdateUserName(UpdateUserNameCommand command) {
        logger.info("Updating user name in tournament satellite satellite: " + command.getSatelliteAggregateId());
        tournamentSatelliteSatelliteService.updateUserName(
                command.getSatelliteAggregateId(),
                command.getExecutionAggregateId(),
                command.getEventVersion(),
                command.getUserAggregateId(),
                command.getName(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleUpdateParticipantAnswer(UpdateParticipantAnswerCommand command) {
        logger.info("Updating participant answer: satellite=" + command.getSatelliteAggregateId() + ", student="
                + command.getStudentAggregateId());
        return tournamentSatelliteSatelliteService.updateParticipantAnswer(
                command.getSatelliteAggregateId(),
                command.getStudentAggregateId(),
                command.getQuizAnswerAggregateId(),
                command.getQuestionAggregateId(),
                command.isCorrect(),
                command.getEventVersion(),
                command.getUnitOfWork());
    }
}
